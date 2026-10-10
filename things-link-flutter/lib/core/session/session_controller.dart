import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../features/auth/auth_api.dart';
import '../../features/project_access/project_entry.dart';
import '../network/app_http_client.dart';
import 'app_session.dart';
import 'session_storage.dart';

/// 会话变更串行持久化；入口代次在排队前推进，使旧响应无法发布到新界面。
class SessionController extends ChangeNotifier {
  SessionController(this.storage, this.api);
  final SessionStorage storage;
  final AuthApi api;
  SessionSnapshot _snapshot = const SessionSnapshot();
  ProjectEntry? _target;
  AppSession? _current;
  Future<void> _tail = Future.value();
  Future<void>? _refreshJob;
  int _epoch = 0;
  int _waiting = 0;
  bool _loaded = false;
  bool projectTransition = false;
  bool _disposed = false;
  String? error;
  String? get targetId => _target?.id;
  AppSession? get current =>
      _current?.entry.id == _target?.id ? _current : null;
  bool get busy => _waiting > 0;

  /// 切项目或退出立即推进，供业务请求废弃旧身份结果；令牌轮换不推进。
  int get scopeRevision => _epoch;
  bool get loaded => _loaded;
  bool get hasRetainedSession => _snapshot.active != null;
  int get pendingCount => _snapshot.pending.length;

  @override
  void notifyListeners() {
    if (!_disposed) super.notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _epoch++;
    super.dispose();
  }

  Future<void> _run(Future<void> Function() action) {
    _waiting++;
    notifyListeners();
    final done = _tail.then((_) async {
      error = null;
      try {
        await action();
      } on AppRequestFailure catch (e) {
        error = e.message;
      } catch (_) {
        error = '无法读写安全登录信息，操作未完成，请重试。';
      } finally {
        _waiting--;
        notifyListeners();
      }
    });
    _tail = done;
    return done;
  }

  Future<void> _load() async {
    if (_loaded) return;
    _snapshot = SessionSnapshot.decode(await storage.read());
    _loaded = true;
  }

  Future<void> _write(SessionSnapshot next) async {
    await storage.write(next.encode());
    _snapshot = next;
  }

  List<PendingRevocation> _withRevocation(AppSession session) {
    final pending = [..._snapshot.pending];
    if (!pending.any(
      (p) =>
          p.entry.baseUrl == session.entry.baseUrl &&
          p.refreshToken == session.refreshToken,
    )) {
      if (pending.length >= 50) {
        throw const AppRequestFailure('待远端退出记录已满，请联网重试退出。');
      }
      pending.add(PendingRevocation(session.entry, session.refreshToken));
    }
    return pending;
  }

  Future<void> _retire(AppSession session) async {
    await _write(SessionSnapshot(pending: _withRevocation(session)));
    _current = null;
  }

  Future<void> _drain() async {
    // 每次最多三项，首个网络失败即停，不以无限重试阻塞当前操作。
    for (var i = 0; i < 3 && _snapshot.pending.isNotEmpty; i++) {
      final item = _snapshot.pending.first;
      try {
        await api.logout(item.entry, item.refreshToken);
      } catch (_) {
        return;
      }
      await _write(
        SessionSnapshot(
          active: _snapshot.active,
          rotating: _snapshot.rotating,
          pending: _snapshot.pending.skip(1).toList(),
        ),
      );
    }
  }

  Future<void> activate(ProjectEntry? entry) {
    _target = entry;
    _current = null;
    final epoch = ++_epoch;
    return _run(() async {
      await _load();
      if (epoch != _epoch || _disposed) return;
      final saved = _snapshot.active;
      if (saved != null) {
        if (saved.entry.id == entry?.id &&
            !_snapshot.rotating &&
            saved.refreshExpiresAt.isAfter(DateTime.now())) {
          await _rotate(epoch);
        } else {
          await _retire(saved);
          if (saved.entry.id == entry?.id) {
            error = '上次会话已过期或刷新结果不确定，请重新登录。';
          }
        }
      }
      await _drain();
    });
  }

  Future<void> login(String username, String password) {
    final entry = _target;
    final epoch = _epoch;
    return _run(() async {
      await _load();
      if (entry == null || epoch != _epoch || _disposed) return;
      if (username.trim().isEmpty ||
          username.trim().length > 64 ||
          password.isEmpty ||
          password.length > 128) {
        throw const AppRequestFailure('请填写有效的账号和密码。');
      }
      final saved = _snapshot.active;
      if (saved != null) await _retire(saved);
      await _drain();
      if (epoch != _epoch || _disposed) return;
      if (_snapshot.pending.length >= 49) {
        throw const AppRequestFailure('待远端退出记录过多，请联网重试退出后登录。');
      }
      final issued = await api.login(entry, username.trim(), password);
      try {
        if (epoch != _epoch || _disposed) {
          await _retire(issued);
          await _drain();
          return;
        }
        await _write(
          SessionSnapshot(active: issued, pending: _snapshot.pending),
        );
        if (epoch != _epoch || _disposed) {
          await _retire(issued);
          await _drain();
          return;
        }
        _current = issued;
      } catch (_) {
        // 保存失败也不发布登录成功；尽力撤销刚签发而未可靠落盘的令牌。
        try {
          await api.logout(issued.entry, issued.refreshToken);
        } catch (_) {}
        rethrow;
      }
    });
  }

  Future<void> _rotate(int epoch) async {
    final saved = _snapshot.active;
    if (saved == null || epoch != _epoch || _disposed) return;
    _current = null;
    if (_snapshot.rotating || !saved.refreshExpiresAt.isAfter(DateTime.now())) {
      await _retire(saved);
      throw const AppRequestFailure('会话已失效，请重新登录。');
    }
    await _write(
      SessionSnapshot(
        active: saved,
        rotating: true,
        pending: _snapshot.pending,
      ),
    );
    if (epoch != _epoch || _disposed) {
      await _retire(saved);
      return;
    }
    try {
      final issued = await api.refresh(saved);
      if (epoch != _epoch || _disposed) {
        await _retire(issued);
        return;
      }
      await _write(SessionSnapshot(active: issued, pending: _snapshot.pending));
      if (epoch != _epoch || _disposed) {
        await _retire(issued);
        return;
      }
      _current = issued;
    } catch (_) {
      // 旧令牌可能已轮换，不能重放；旧族仍可通过幂等logout撤销。
      await _retire(saved);
      throw const AppRequestFailure('会话验证未完成，请重新登录；远端退出将继续重试。');
    }
  }

  Future<void> refresh() {
    final existing = _refreshJob;
    if (existing != null) return existing;
    final epoch = _epoch;
    final job = _run(() async {
      await _drain();
      await _rotate(epoch);
    });
    _refreshJob = job;
    unawaited(
      job.whenComplete(() {
        if (identical(_refreshJob, job)) _refreshJob = null;
      }),
    );
    return job;
  }

  Future<String?> accessFor(ProjectEntry entry) async {
    if (targetId == entry.id && _refreshJob != null) await _refreshJob;
    if (current?.entry.id != entry.id) return null;
    if (!current!.accessExpiresAt.isAfter(
      DateTime.now().add(const Duration(seconds: 30)),
    )) {
      await refresh();
    }
    return current?.entry.id == entry.id ? current?.accessToken : null;
  }

  /// 换签和刷新共用持久化队列；未知结果不重放，目标可靠保存后才发布。
  Future<void> switchProject(
    Future<AppSession> Function(AppSession source) issue,
    Future<void> Function(ProjectEntry target) persistEntry,
  ) {
    final epoch = ++_epoch;
    _current = null;
    return _run(() async {
      await _load();
      if (epoch != _epoch || _disposed) return;
      var source = _snapshot.active;
      if (source == null || _snapshot.rotating) {
        throw const AppRequestFailure('请重新登录后切换项目。');
      }
      if (!source.accessExpiresAt.isAfter(
        DateTime.now().add(const Duration(seconds: 30)),
      )) {
        await _rotate(epoch);
        source = _snapshot.active;
      }
      if (source == null || epoch != _epoch || _disposed) return;
      _current = null;
      await _write(
        SessionSnapshot(
          active: source,
          rotating: true,
          pending: _snapshot.pending,
        ),
      );
      AppSession? issued;
      try {
        issued = await issue(source);
        if (epoch != _epoch || _disposed) {
          await _retire(issued);
          await _drain();
          return;
        }
        await _write(
          SessionSnapshot(active: issued, pending: _snapshot.pending),
        );
        if (epoch != _epoch || _disposed) {
          await _retire(issued);
          await _drain();
          return;
        }
        projectTransition = true;
        _target = issued.entry;
        await persistEntry(issued.entry);
        if (epoch != _epoch || _disposed) {
          await _retire(issued);
          await _drain();
          return;
        }
        _current = issued;
      } catch (failure) {
        if (issued == null &&
            failure is AppRequestFailure &&
            [400, 403, 404, 429].contains(failure.status) &&
            epoch == _epoch &&
            !_disposed) {
          await _write(
            SessionSnapshot(active: source, pending: _snapshot.pending),
          );
          _current = source;
          rethrow;
        }
        _current = null;
        if (issued != null) {
          try {
            await api.logout(issued.entry, issued.refreshToken);
          } catch (_) {}
        }
        await _retire(issued ?? source);
        await _drain();
        throw const AppRequestFailure('项目切换结果未确认，请重新登录。');
      } finally {
        projectTransition = false;
      }
    });
  }

  Future<void> logout() {
    _epoch++;
    _current = null;
    return _run(() async {
      await _load();
      final saved = _snapshot.active;
      if (saved != null) await _retire(saved);
      await _drain();
    });
  }

  Future<void> retryPending() => _run(() async {
    await _load();
    await _drain();
  });
}
