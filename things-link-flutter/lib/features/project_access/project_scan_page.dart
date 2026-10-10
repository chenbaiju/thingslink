import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'project_entry.dart';

/// 扫描仅返回经过校验的入口，不保存、不登录、不打印二维码正文。
class ProjectScanPage extends StatefulWidget {
  const ProjectScanPage({super.key});
  @override
  State<ProjectScanPage> createState() => _ProjectScanPageState();
}

class _ProjectScanPageState extends State<ProjectScanPage>
    with WidgetsBindingObserver {
  bool _returned = false;
  bool _invalid = false;
  final _camera = MobileScannerController(formats: [BarcodeFormat.qrCode]);
  final Future<bool> _iosSimulator = Platform.isIOS
      ? const MethodChannel('thingsx/environment')
            .invokeMethod<bool>('isSimulator')
            .then((value) => value == true)
      : Future.value(false);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  Future<void> _cameraAction(bool start) async {
    try {
      if (start) {
        await _camera.start();
      } else {
        await _camera.stop();
      }
    } catch (_) {
      // 原生错误由扫描控件呈现，退出时不让相机清理异常阻止返回。
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!_camera.value.hasCameraPermission || _returned) return;
    if (state == AppLifecycleState.resumed) unawaited(_cameraAction(true));
    if (state == AppLifecycleState.inactive) unawaited(_cameraAction(false));
  }

  Future<void> _finish([ProjectEntry? entry]) async {
    if (_returned) return;
    _returned = true;
    await _cameraAction(false);
    if (mounted) Navigator.of(context).pop(entry);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_cameraAction(false).then((_) => _camera.dispose()));
    super.dispose();
  }

  void _detect(BarcodeCapture capture) {
    if (_returned || !mounted) return;
    for (final code in capture.barcodes) {
      if (code.format != BarcodeFormat.qrCode || code.rawValue == null) {
        continue;
      }
      try {
        final entry = ProjectEntry.parse(code.rawValue!);
        unawaited(_finish(entry));
        return;
      } on FormatException {
        if (!_invalid) setState(() => _invalid = true);
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('扫描项目入口')),
    body: SafeArea(
      child: Column(
        children: [
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text('扫描 Console 提供的项目二维码，识别后请核对平台地址。'),
          ),
          Expanded(
            child: FutureBuilder<bool>(
              future: _iosSimulator,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.data == true) {
                  return const Center(
                    child: Padding(
                      padding: EdgeInsets.all(24),
                      child: Text('iOS 模拟器不支持相机扫码，请返回手动填写。'),
                    ),
                  );
                }
                return MobileScanner(
                  controller: _camera,
                  useAppLifecycleState: false,
                  onDetect: _detect,
                  errorBuilder: (context, error) => Center(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.no_photography_outlined, size: 48),
                          const SizedBox(height: 16),
                          Text(
                            error.errorCode ==
                                    MobileScannerErrorCode.permissionDenied
                                ? '未获得相机权限。可在系统设置中允许相机，或返回手动填写。'
                                : '相机暂不可用，请重试或返回手动填写。',
                          ),
                          const SizedBox(height: 16),
                          OutlinedButton(
                            onPressed: () => _cameraAction(true),
                            child: const Text('重试相机'),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                if (_invalid) const Text('不是受支持的项目入口码，请继续扫描正确的二维码。'),
                TextButton(
                  onPressed: () => _finish(),
                  child: const Text('返回手动填写'),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}
