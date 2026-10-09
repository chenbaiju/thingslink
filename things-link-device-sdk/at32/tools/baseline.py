#!/usr/bin/env python3
"""只读核验原AT32工程、应用边界和历史制品；不构建、不烧录。"""

import argparse
import ast
import hashlib
import json
import operator
from pathlib import Path, PureWindowsPath
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


class AuditError(ValueError):
    """输入不完整或无法在已支持范围内安全解释。"""


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_text(path):
    data = path.read_bytes()
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise AuditError("源码编码不受支持")


def within(root, path):
    """解析软链接后仍须留在原工程边界内。"""
    path = path.resolve()
    if not path.is_relative_to(root.resolve()):
        raise AuditError("工程引用越过来源根目录")
    return path


def source_inputs(root, project):
    """绑定工程及全部C/C++、头文件和汇编输入，覆盖隐式include。"""
    paths = {project}
    for path in root.rglob("*"):
        if path.suffix.lower() in (".c", ".h", ".cpp", ".hpp", ".s", ".inc") and path.is_file():
            if not any(part in ("_build", "Listings", "BIN") for part in path.relative_to(root).parts):
                paths.add(within(root, path))
    return paths


def integer(expression, macros, visiting=None):
    """仅解释布局所需的整数表达式，不执行C或Python代码。"""
    visiting = set() if visiting is None else visiting
    try:
        tree = ast.parse(expression.strip(), mode="eval")
    except SyntaxError as error:
        raise AuditError("无法解释布局表达式") from error

    def value(node):
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return node.value
        if isinstance(node, ast.Name):
            if node.id not in macros or node.id in visiting:
                raise AuditError("布局引用缺失或循环")
            return integer(macros[node.id], macros, visiting | {node.id})
        operations = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul}
        if isinstance(node, ast.BinOp) and type(node.op) in operations:
            return operations[type(node.op)](value(node.left), value(node.right))
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return -value(node.operand)
        if isinstance(node, ast.Compare) and len(node.ops) == 1 and isinstance(node.ops[0], ast.Eq):
            return int(value(node.left) == value(node.comparators[0]))
        raise AuditError("布局包含未支持的运算")

    return value(tree.body)


def macros_from(text, initial):
    """解释本项目布局头的条件分支；未知语法拒绝而不猜测。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    macros = dict(initial)
    stack = []
    active = True
    for line in text.splitlines():
        line = line.split("//", 1)[0].strip()
        match = re.match(r"#\s*(\w+)\b\s*(.*)", line)
        if not match:
            continue
        directive, body = match.groups()
        if directive in ("ifdef", "ifndef", "if"):
            condition = False
            if active:
                condition = body in macros if directive == "ifdef" else body not in macros
                if directive == "if":
                    condition = bool(integer(body, macros))
            stack.append((active, condition, False))
            active = active and condition
        elif directive == "else":
            if not stack or stack[-1][2]:
                raise AuditError("布局条件分支损坏")
            parent, condition, _ = stack[-1]
            stack[-1] = (parent, condition, True)
            active = parent and not condition
        elif directive == "endif":
            if not stack:
                raise AuditError("布局条件分支不匹配")
            active = stack.pop()[0]
        elif directive == "elif":
            raise AuditError("布局条件分支未受支持")
        elif directive == "define" and active:
            defined = re.fullmatch(r"([A-Za-z_]\w*)(?:\s+(.*))?", body)
            if not defined:
                raise AuditError("布局宏未受支持")
            macros[defined[1]] = defined[2] or "1"
        elif directive not in ("include", "define"):
            raise AuditError("布局指令未受支持")
    if stack:
        raise AuditError("布局条件分支未闭合")
    return macros


def read_layout(root, defined):
    flash = within(root, root / "AT32F4xx/UserLibrary/MCUDrive/Flash.h")
    hardware = within(root, root / "AT32F4xx/UserLibrary/Hardware.h")
    macros = macros_from(source_text(flash), defined)
    text = source_text(hardware)
    end = re.search(r"/\*+\s*END\s*\*+/", text)
    if end is None:
        raise AuditError("找不到原布局段边界")
    # 原布局段位于头文件防重包含分支内，后续IO宏不属于布局解释范围。
    macros = macros_from(text[:end.start()] + "\n#endif\n", macros)
    names = ("BOOTLOADER", "USER_APP", "USER_DATA0", "USER_DATA1", "UPGRADE_DATA")
    regions = []
    for name in names:
        address = name + ("_ADDR" if name == "BOOTLOADER" else "_START_ADDR")
        start = integer(macros[address], macros)
        size = integer(macros[name + "_SIZE"], macros)
        regions.append({"name": name, "start": start, "size": size, "endExclusive": start + size})
    flash_start = regions[0]["start"]
    flash_size = integer(macros["MCU_FLASH_SIZE"], macros) * 1024
    previous = flash_start
    for region in regions:
        if region["size"] <= 0 or region["start"] < previous or region["endExclusive"] > flash_start + flash_size:
            raise AuditError("源码布局重叠或越过声明Flash容量")
        previous = region["endExclusive"]
    return {"flashStart": flash_start, "flashBytes": flash_size, "regions": regions}, [flash, hardware]


def hex_image(path, app):
    """逐记录校验HEX，拒绝重复、缺口及应用区外地址。"""
    data = {}
    base = 0
    ended = False
    for line in path.read_text(encoding="ascii").splitlines():
        try:
            if not line.startswith(":") or ended:
                raise AuditError("HEX格式或结束记录错误")
            record = bytes.fromhex(line[1:])
            if len(record) < 5 or len(record) != record[0] + 5 or sum(record) % 256:
                raise AuditError("HEX长度或校验和错误")
            address, kind = int.from_bytes(record[1:3], "big"), record[3]
            payload = record[4:-1]
            if kind == 0:
                for offset, byte in enumerate(payload):
                    position = base + address + offset
                    if position in data or not app["start"] <= position < app["endExclusive"]:
                        raise AuditError("HEX地址重复或越过应用区")
                    data[position] = byte
            elif kind == 4 and len(payload) == 2 and address == 0:
                base = int.from_bytes(payload, "big") << 16
            elif kind == 1 and len(payload) == 0 and address == 0:
                ended = True
            elif kind == 5 and len(payload) == 4 and address == 0:
                pass
            else:
                raise AuditError("HEX记录类型未受支持")
        except ValueError as error:
            if isinstance(error, AuditError):
                raise
            raise AuditError("HEX编码错误") from error
    if not ended or not data or min(data) != app["start"] or len(data) != max(data) - min(data) + 1:
        raise AuditError("HEX缺结束记录、向量起点或连续应用数据")
    return bytes(data[position] for position in range(min(data), max(data) + 1))


def toolchain(compiler, executable):
    candidate = executable or shutil.which("armcc")
    if not candidate:
        return {"available": False, "matchesDeclaredVersion": False}
    path = Path(candidate).resolve()
    if not path.is_file():
        return {"available": False, "matchesDeclaredVersion": False}
    result = subprocess.run([str(path), "--vsn"], capture_output=True, timeout=10, check=False)
    text = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    declared = re.search(r"V(\d+\.\d+) update (\d+) \(build (\d+)\)", compiler)
    matched = bool(declared and re.search(r"(?<![\d.])" + re.escape(declared[1]) + r"(?![\d.])", text)
                   and re.search(r"\b[Bb]uild\s+" + re.escape(declared[3]) + r"\b", text)) and result.returncode == 0
    return {"available": True, "executable": str(path), "matchesDeclaredVersion": matched}


def audit(root, target_name=None, compiler_path=None):
    root = root.resolve(strict=True)
    projects = list(root.rglob("ZN-DM08-IV.uvprojx"))
    if len(projects) != 1:
        raise AuditError("原工程必须唯一")
    project = within(root, projects[0])
    source_paths = source_inputs(root, project)
    initial_hashes = {path: digest(path) for path in source_paths}
    tree = ET.parse(project)
    targets = tree.findall("./Targets/Target")
    if target_name:
        targets = [target for target in targets if target.findtext("TargetName") == target_name]
    if len(targets) != 1:
        raise AuditError("构建目标不唯一或不存在")
    target = targets[0]
    device = target.findtext(".//Device", "").lstrip("-")
    if device != "AT32F415CBT7":
        raise AuditError("本工具仅支持已审查的AT32F415CBT7目标")
    defined = {}
    for item in target.findall(".//Cads/VariousControls/Define"):
        for entry in (item.text or "").split(","):
            parts = entry.strip().split("=", 1)
            if parts[0]:
                defined[parts[0].strip()] = parts[1].strip() if len(parts) == 2 else "1"
    if defined.get("AT32F415CBT7") != "1" or "AT32F415RCT7" in defined:
        raise AuditError("设备声明与芯片宏不一致")
    files = {project}
    missing = 0
    project_files = target.findall("./Groups/Group/Files/File/FilePath")
    if not project_files:
        raise AuditError("工程没有显式文件输入")
    for item in project_files:
        if (not item.text or "$" in item.text or PureWindowsPath(item.text).drive
                or Path(item.text.replace("\\", "/")).is_absolute()):
            raise AuditError("工程文件引用未受支持")
        path = within(root, project.parent / item.text.replace("\\", "/"))
        if path.is_file():
            files.add(path)
        else:
            missing += 1
    layout, headers = read_layout(root, defined)
    files.update(headers)
    app = next(region for region in layout["regions"] if region["name"] == "USER_APP")
    linkers = target.findall(".//OnChipMemories/OCR_RVCT4")
    if len(linkers) != 1:
        raise AuditError("链接应用区不唯一")
    linker_start = int(linkers[0].findtext("StartAddress"), 0)
    linker_size = int(linkers[0].findtext("Size"), 0)
    issues = []
    if missing:
        issues.append({"code": "PROJECT_SOURCES_MISSING", "count": missing})
    if linker_start != app["start"] or linker_size != app["size"]:
        issues.append({"code": "LINKER_LAYOUT_CONFLICT"})
    hex_path = within(root, project.parent / "_build/APP_MCU.hex")
    bin_path = within(root, project.parent / "BIN/APP_MCU.bin")
    artifact_hashes = {path: digest(path) for path in (hex_path, bin_path)}
    files.update(source_paths)
    for path in files:
        if path not in initial_hashes:
            initial_hashes[path] = digest(path)
    image = hex_image(hex_path, app)
    binary = bin_path.read_bytes()
    if binary != image:
        issues.append({"code": "HISTORICAL_HEX_BIN_DIFFER"})
    compiler = target.findtext("pArmCC", "")
    if "V5.06 update 6 (build 750)" not in compiler:
        raise AuditError("声明编译器不属于已审查基线")
    compiler_result = toolchain(compiler, compiler_path)
    if not compiler_result["matchesDeclaredVersion"]:
        issues.append({"code": "DECLARED_COMPILER_UNAVAILABLE"})
    if source_inputs(root, project) != source_paths:
        raise AuditError("审查期间来源清单发生改变")
    for path, original_hash in (initial_hashes | artifact_hashes).items():
        if digest(path) != original_hash:
            raise AuditError("审查期间来源发生改变")
    manifest = [{"path": path.relative_to(root).as_posix(), "sha256": initial_hashes[path]}
                for path in sorted(files, key=lambda item: item.relative_to(root).as_posix())]
    source_binding = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"schemaVersion": 1, "scope": "JG-M01-BASELINE-2", "target": target.findtext("TargetName"),
            "declaredDevice": device, "project": project.relative_to(root).as_posix(), "compilerDeclaration": compiler,
            "sourceBindingSha256": source_binding, "sourceBindingMethod": "project-and-entire-c-cpp-header-assembly-tree",
            "projectInputFiles": manifest, "missingProjectFiles": missing,
            "sourceLayout": layout, "projectLinker": {"start": linker_start, "size": linker_size},
            "historicalImage": {"hexSha256": artifact_hashes[hex_path], "binSha256": artifact_hashes[bin_path],
                                "imageBytes": len(image), "imageFitsSourceApp": True, "hexMatchesBin": binary == image,
                                "sourceAppRemainingBytes": app["size"] - len(image)},
            "toolchain": compiler_result, "issues": issues, "sourcePreflightPassed": not issues,
            "firmwareRebuilt": False, "hardwareQualified": False, "flashExecuted": False,
            "releaseQualified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--target")
    parser.add_argument("--armcc", type=Path)
    args = parser.parse_args()
    try:
        root = args.source_root.resolve(strict=True)
        report = args.report.resolve()
        if report.is_relative_to(root) or report.exists():
            raise AuditError("报告须在原工程之外，且不得覆盖已有文件")
        result = audit(root, args.target, args.armcc)
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print(json.dumps({"report": str(report), "sourcePreflightPassed": result["sourcePreflightPassed"],
                          "issues": result["issues"], "releaseQualified": False}, ensure_ascii=False))
        return 0 if result["sourcePreflightPassed"] else 2
    except (AuditError, OSError, ET.ParseError, subprocess.SubprocessError, ValueError):
        # 不回显原源码、宏值、编译器输出或异常中的潜在敏感内容。
        print("原板基线检查失败：输入、路径、布局或工具链不满足已支持条件。", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
