import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../../core/session/session_read.dart';
import 'alarm_api.dart';

class AlarmListController extends ChangeNotifier {
  AlarmListController(
    this.sessions,
    this.api, {
    this.initialFilter = const AlarmFilter(),
    bool active = true,
  }) {
    _active = active;
    filter = initialFilter;
    sessions.addListener(_sessionChanged);
    _sessionChanged();
  }
  final SessionController sessions;
  final AlarmApi api;
  List<AppAlarm> items = [];
  final AlarmFilter initialFilter;
  late AlarmFilter filter;
  String? nextCursor, error;
  bool loading = false, loaded = false;
  int _generation = 0;
  int? _scope;
  String? _identity, _token;
  bool _disposed = false;
  late bool _active;
  String? _pageCursor;
  final List<String?> _previous = [];
  int get pageNumber => _previous.length + 1;
  bool get canPrevious => _previous.isNotEmpty;
  void setActive(bool value) {
    if (_disposed || value == _active) return;
    _active = value;
    _clear();
    notifyListeners();
    if (value && sessions.current != null) unawaited(refresh());
  }

  String? get _currentIdentity => sessions.current == null
      ? null
      : '${sessions.current!.entry.id}\u0000${sessions.current!.username}';
  void _clear() {
    _generation++;
    items = [];
    nextCursor = null;
    error = null;
    loaded = false;
    loading = false;
  }

  void _sessionChanged() {
    if (_disposed) return;
    if (_scope != sessions.scopeRevision) {
      _scope = sessions.scopeRevision;
      _clear();
      _identity = null;
      _token = null;
      _pageCursor = null;
      _previous.clear();
      filter = initialFilter;
      notifyListeners();
    }
    if (sessions.current == null) {
      if (!sessions.busy) {
        _clear();
        _identity = null;
        _token = null;
        notifyListeners();
      }
      return;
    }
    final changed =
        _identity != _currentIdentity ||
        _token != sessions.current!.accessToken;
    if (_identity != _currentIdentity) _clear();
    _identity = _currentIdentity;
    _token = sessions.current!.accessToken;
    if (_active && changed && !loading) unawaited(refresh());
  }

  Future<void> search(AlarmFilter value) async {
    filter = value;
    _pageCursor = null;
    _previous.clear();
    await refresh();
  }

  Future<void> refresh() => _load(_pageCursor);
  Future<void> more() async {
    if (!loading && nextCursor != null) {
      _previous.add(_pageCursor);
      _pageCursor = nextCursor;
      await refresh();
    }
  }

  Future<void> previous() async {
    if (!loading && _previous.isNotEmpty) {
      _pageCursor = _previous.removeLast();
      await refresh();
    }
  }

  Future<void> first() async {
    _previous.clear();
    _pageCursor = null;
    await refresh();
  }

  Future<void> _load(String? cursor) async {
    final session = sessions.current;
    if (session == null || _disposed || !_active) return;
    final currentFilter = filter;
    final generation = ++_generation;
    final identity = _currentIdentity;
    bool valid() =>
        !_disposed && generation == _generation && identity == _currentIdentity;
    items = [];
    nextCursor = null;
    loaded = false;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final page = await readWithSession(
        sessions,
        session.entry,
        (token) => api.list(
          session.entry,
          token,
          filter: currentFilter,
          cursor: cursor,
        ),
        valid,
      );
      if (!valid()) return;
      if (page.nextCursor != null &&
          (page.nextCursor == cursor || _previous.contains(page.nextCursor))) {
        throw const AppRequestFailure('告警分页未前进，请刷新列表。');
      }
      final merged = <String, AppAlarm>{for (final d in page.items) d.id: d};
      items = List.unmodifiable(merged.values);
      nextCursor = page.nextCursor;
      loaded = true;
    } on StaleSessionRead {
      // 切换身份或条件后不恢复旧视图。
    } catch (failure) {
      if (valid()) {
        items = [];
        nextCursor = null;
        loaded = false;
        error = failure is AppRequestFailure
            ? failure.message
            : '告警列表暂不可用，请重试。';
      }
    } finally {
      if (!_disposed && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    sessions.removeListener(_sessionChanged);
    super.dispose();
  }
}
