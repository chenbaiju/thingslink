#!/usr/bin/env python3
"""只读比较历史AXF/HEX/BIN与旧封装包，不生成或修改固件。"""

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

import baseline


DATE_LITERAL = re.compile(
    rb"(?:[0-2][0-9]:[0-5][0-9]:[0-5][0-9]|"
    rb"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [ 0-3][0-9] [12][0-9]{3})\x00"
)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def elf_load_image(data, app):
    """仅支持本历史候选的ELF32小端ARM单一应用LOAD段。"""
    if len(data) < 52 or data[:7] != b"\x7fELF\x01\x01\x01":
        raise baseline.AuditError("AXF不是已支持的ELF32小端格式")
    header = struct.unpack_from("<16sHHIIIIIHHHHHH", data)
    if header[1:4] != (2, 40, 1) or header[8] != 52 or header[9] != 32 or not header[10]:
        raise baseline.AuditError("AXF目标或段表未受支持")
    offset, count = header[5], header[10]
    if offset < 52 or offset + count * 32 > len(data):
        raise baseline.AuditError("AXF段表越界")
    loads = [entry for n in range(count)
             if (entry := struct.unpack_from("<IIIIIIII", data, offset + n * 32))[0] == 1]
    if len(loads) != 1:
        raise baseline.AuditError("AXF应用LOAD段不唯一")
    _, file_offset, virtual, physical, file_size, memory_size, flags, alignment = loads[0]
    if (virtual != physical or physical != app["start"] or not 0 < file_size <= app["size"]
            or memory_size < file_size or file_offset < 52 or file_offset + file_size > len(data)
            or not physical <= (header[4] & ~1) < physical + file_size):
        raise baseline.AuditError("AXF应用段越界或布局不受支持")
    return data[file_offset:file_offset + file_size], {
        "entry": header[4], "loadAddress": physical, "fileBytes": file_size,
        "segmentMemoryBytes": memory_size, "segmentFlags": flags,
        "interpretation": "historical-single-application-load-only",
    }


def main_region(map_text, app):
    """按历史map的输入区间定位main，不从函数大小猜测字面量池。"""
    entries = []
    pattern = re.compile(
        r"^\s*(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)"
        r"\s+Code\s+RO\s+\d+\s+i\.main\s+main\.o\s*$", re.M
    )
    for match in pattern.finditer(map_text):
        execution, load, size = (int(value, 16) for value in match.groups())
        if execution != load or size <= 0 or not app["start"] <= load < load + size <= app["endExclusive"]:
            raise baseline.AuditError("map的main区域不受支持")
        entries.append({"start": load, "size": size, "endExclusive": load + size})
    if len(entries) != 1:
        raise baseline.AuditError("map的main区域不唯一")
    return entries[0]


def compare_build_literals(left, right, image_start, region):
    """只输出形如编译日期/时间的内容，其他差异只给偏移，不输出原字节。"""
    if len(left) != len(right):
        return {"applicationsIdentical": False, "sameSize": False, "buildLiteralsOnly": False}
    start, end = region["start"] - image_start, region["endExclusive"] - image_start
    if not 0 <= start < end <= len(left):
        raise baseline.AuditError("main区域不在镜像内")

    def literals(blob):
        result = []
        months = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split()
        for match in DATE_LITERAL.finditer(blob[start:end]):
            text = match.group()[:-1].decode("ascii")
            try:
                if ":" in text:
                    datetime.time(*(int(part) for part in text.split(":")))
                else:
                    month, day, year = text.split()
                    datetime.date(int(year), months.index(month) + 1, int(day))
            except ValueError:
                continue
            result.append({"offset": start + match.start(), "size": len(match.group()), "value": text})
        return result

    first, second = literals(left), literals(right)
    locations = [(item["offset"], item["size"]) for item in first]
    same_locations = locations == [(item["offset"], item["size"]) for item in second]
    changed = [index for index, (a, b) in enumerate(zip(left, right)) if a != b]
    covered = {index for offset, size in locations for index in range(offset, offset + size)}
    return {"applicationsIdentical": not changed, "sameSize": True, "differentByteCount": len(changed),
            "differentOffsets": changed, "differentAddresses": [image_start + index for index in changed],
            "leftBuildLiterals": first, "rightBuildLiterals": second,
            "sameLiteralLocations": same_locations,
            "buildLiteralsOnly": bool(changed and locations and same_locations and set(changed) <= covered),
            "otherDifferentByteCount": len(set(changed) - covered)}


def crc16_modbus(data):
    value = 0xFFFF
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ 0xA001 if value & 1 else value >> 1
    return value


def without_comments(text):
    """去掉C注释，保留字符串/字符字面量，避免把注释当作源码接线证据。"""
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/', re.S)
    return tokens.sub(lambda match: " " if match.group().startswith(("//", "/*")) else match.group(), text)


def code_only(text):
    """源码接线检查排除注释与字面量内容，避免打印文本冒充宏参数。"""
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', "STRING", without_comments(text))


def legacy_package(package, key_source, app):
    """按已核验的旧源码解密；密钥与完整明文只存在于进程内。"""
    # 可选依赖只在显式启用封装包审查时加载；不联网安装。
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    declaration = re.findall(r'const char\s+g_udp_aeskey\[\]\s*=\s*"([^"\r\n]+)"', key_source)
    if len(declaration) != 1 or len(package) % 16 or not package:
        raise baseline.AuditError("旧封装输入未受支持")
    key = declaration[0].encode("ascii")
    if len(key) != 32:
        raise baseline.AuditError("旧密钥长度未受支持")
    decryptor = Cipher(algorithms.AES(key), modes.ECB()).decryptor()
    plain = decryptor.update(package) + decryptor.finalize()
    markers = [match.start() for match in re.finditer(b"JGZN", plain[max(0, len(plain) - 128):])]
    if len(markers) != 1:
        raise baseline.AuditError("旧包尾标记不唯一")
    marker = max(0, len(plain) - 128) + markers[0]
    if marker + 32 > len(plain):
        raise baseline.AuditError("旧包尾不完整")
    magic, length, crc, device_type, version, timestamp, reserved, ending = struct.unpack_from("<4sIHHHI7s7s", plain, marker)
    if (ending != b"UPGRADE" or not 0 < length <= app["size"] or length > marker
            or crc16_modbus(plain[:length]) != crc):
        raise baseline.AuditError("旧包长度、结束标记或CRC不满足")
    return plain[:length], {"algorithm": "AES-256-ECB", "trailerOffset": marker,
                           "imageBytes": length, "storedCRC16": crc, "crcMatches": True,
                           "deviceType": device_type, "version": version, "timestampRaw": timestamp,
                           "applicationSha256": sha256(plain[:length]),
                           "paddingAndReservedFieldsQualified": False, "signatureQualified": False}


def audit_artifacts(root, inspect_package=False):
    root = root.resolve(strict=True)
    base = baseline.audit(root)
    project = baseline.within(root, root / base["project"]).parent
    source_paths = baseline.source_inputs(root, project / "ZN-DM08-IV.uvprojx")
    bound_paths = {baseline.within(root, root / item["path"]) for item in base["projectInputFiles"]}
    if not source_paths <= bound_paths:
        raise baseline.AuditError("来源清单与基线回执不一致")
    app = next(region for region in base["sourceLayout"]["regions"] if region["name"] == "USER_APP")
    paths = {"axf": project / "_build/APP_MCU.axf", "hex": project / "_build/APP_MCU.hex",
             "bin": project / "BIN/APP_MCU.bin", "map": project / "Listings/APP_MCU.map",
             "mainSource": project / "UserApp/main.c", "configSource": project / "UserApp/Config.c"}
    if inspect_package:
        packages = list((project / "BIN").glob("Updata*.bin"))
        if len(packages) != 1:
            raise baseline.AuditError("旧封装包不唯一")
        paths.update(package=packages[0], keySource=root / "GeneralFun/udp_client/udp_client_api.c")
    paths = {name: baseline.within(root, path) for name, path in paths.items()}
    hashes = {name: baseline.digest(path) for name, path in paths.items()}
    binary = paths["bin"].read_bytes()
    elf_image, elf = elf_load_image(paths["axf"].read_bytes(), app)
    hex_image = baseline.hex_image(paths["hex"], app)
    region = main_region(baseline.source_text(paths["map"]), app)
    issues = []
    if elf_image != binary or hex_image != binary:
        issues.append({"code": "HISTORICAL_APPLICATIONS_DIFFER"})
    source_date = bool(re.search(r"^\s*#define\s+COMPILE_TIME\s+__DATE__\b",
                               code_only(baseline.source_text(paths["configSource"])), re.M))
    source_print = bool(re.search(r"printf\([^;]*\bCOMPILE_TIME\s*,\s*__TIME__\s*\)",
                                code_only(baseline.source_text(paths["mainSource"]))))
    package_result = {"inspected": False}
    if inspect_package:
        image, package_result = legacy_package(paths["package"].read_bytes(), baseline.source_text(paths["keySource"]), app)
        comparison = compare_build_literals(binary, image, app["start"], region)
        package_result.update(inspected=True, comparison=comparison,
                              buildLiteralSourceEvidence=source_date and source_print,
                              differenceExplanationSupported=comparison.get("buildLiteralsOnly", False) and source_date and source_print)
        if not comparison["applicationsIdentical"] and not package_result["differenceExplanationSupported"]:
            issues.append({"code": "PACKAGE_APPLICATION_DIFFERENCE_UNEXPLAINED"})
    # 与本次来源绑定回执交叉检查，避免先绑定源、后绑定制品时来源已改变。
    for item in base["projectInputFiles"]:
        if baseline.digest(baseline.within(root, root / item["path"])) != item["sha256"]:
            raise baseline.AuditError("来源与基线回执不一致")
    for name, path in paths.items():
        if baseline.digest(path) != hashes[name]:
            raise baseline.AuditError("审查期间制品或来源发生改变")
    if baseline.source_inputs(root, project / "ZN-DM08-IV.uvprojx") != source_paths:
        raise baseline.AuditError("审查期间来源清单发生改变")
    return {"schemaVersion": 1, "scope": "JG-M01-BASELINE-2b", "sourceBindingSha256": base["sourceBindingSha256"],
            "inputs": {name: {"path": str(path.relative_to(root).as_posix()), "sha256": hashes[name]}
                       for name, path in paths.items()},
            "baselineIssues": base["issues"], "sourcePreflightPassed": base["sourcePreflightPassed"],
            "axf": elf, "axfImageSha256": sha256(elf_image), "axfMatchesBin": elf_image == binary,
            "hexMatchesBin": hex_image == binary, "mapMainRegion": region,
            "legacyPackage": package_result, "issues": issues, "staticAuditComplete": not issues,
            "firmwareRebuilt": False, "hardwareQualified": False, "flashExecuted": False, "releaseQualified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--inspect-legacy-package", action="store_true")
    args = parser.parse_args()
    try:
        root, report = args.source_root.resolve(strict=True), args.report.resolve()
        if report.is_relative_to(root) or report.exists():
            raise baseline.AuditError("报告路径不满足")
        result = audit_artifacts(root, args.inspect_legacy_package)
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        print(json.dumps({"report": str(report), "staticAuditComplete": result["staticAuditComplete"],
                          "issues": result["issues"], "releaseQualified": False}, ensure_ascii=False))
        return 0 if result["staticAuditComplete"] else 2
    except (OSError, ValueError, ImportError, struct.error, baseline.ET.ParseError, baseline.subprocess.SubprocessError):
        print("历史制品检查失败：输入、依赖或受支持格式不满足。", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
