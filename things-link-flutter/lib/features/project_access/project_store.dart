import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'project_entry.dart';

abstract interface class ProjectStorage {
  Future<String?> read();
  Future<void> write(String value);
}

/// 单个版本化快照保存入口与选择；失败时保留当前状态，不覆盖损坏的原值。
class ProjectStore extends ChangeNotifier {
  ProjectStore(this.storage);
  final ProjectStorage storage;
  List<ProjectEntry> _entries = [];
  String? _selectedId;
  bool _disposed = false;
  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  @override
  void notifyListeners() {
    if (!_disposed) super.notifyListeners();
  }

  bool loaded = false;
  bool busy = false;
  String? error;
  List<ProjectEntry> get entries => List.unmodifiable(_entries);
  ProjectEntry? get selected =>
      _entries.where((e) => e.id == _selectedId).firstOrNull;

  Future<void> load() async {
    if (busy) return;
    busy = true;
    error = null;
    notifyListeners();
    try {
      final raw = await storage.read();
      var entries = <ProjectEntry>[];
      String? selectedId;
      if (raw != null) {
        if (raw.length > 200000) throw const FormatException();
        final data = jsonDecode(raw);
        if (data is! Map<String, dynamic> ||
            data['version'] is! int ||
            data['version'] != 1 ||
            data['entries'] is! List ||
            (data['entries'] as List).length > 50 ||
            (data['selectedId'] != null && data['selectedId'] is! String)) {
          throw const FormatException();
        }
        entries = (data['entries'] as List).map(ProjectEntry.fromJson).toList();
        selectedId = data['selectedId'] as String?;
        if (entries.map((e) => e.id).toSet().length != entries.length ||
            (entries.isNotEmpty && !entries.any((e) => e.id == selectedId)) ||
            (entries.isEmpty && selectedId != null)) {
          throw const FormatException();
        }
      }
      _entries = entries;
      _selectedId = selectedId;
      loaded = true;
    } catch (_) {
      loaded = false;
      error = '暂时无法读取项目入口，请重试。原有信息未被覆盖。';
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> save(ProjectEntry entry) async {
    final next = [..._entries];
    final i = next.indexWhere((e) => e.id == entry.id);
    if (i >= 0) {
      next[i] = entry;
    } else {
      if (next.length >= 50) throw const FormatException('最多保存 50 个项目入口');
      next.add(entry);
    }
    await _persist(next, entry.id);
  }

  Future<void> select(String id) async {
    if (!_entries.any((e) => e.id == id)) throw StateError('项目入口不存在');
    await _persist(_entries, id);
  }

  Future<void> remove(String id) async {
    final next = _entries.where((e) => e.id != id).toList();
    await _persist(
      next,
      id == _selectedId ? next.firstOrNull?.id : _selectedId,
    );
  }

  Future<void> _persist(List<ProjectEntry> entries, String? selectedId) async {
    if (!loaded || busy) throw StateError('项目入口尚未就绪');
    busy = true;
    error = null;
    notifyListeners();
    try {
      await storage.write(
        jsonEncode({
          'version': 1,
          'entries': entries.map((e) => e.toJson()).toList(),
          'selectedId': selectedId,
        }),
      );
      _entries = List.of(entries);
      _selectedId = selectedId;
    } catch (_) {
      error = '保存失败，请重试。当前选择未变更。';
      rethrow;
    } finally {
      busy = false;
      notifyListeners();
    }
  }
}
