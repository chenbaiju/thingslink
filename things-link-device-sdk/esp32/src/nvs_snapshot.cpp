#include "thingslink/nvs_snapshot.hpp"
#include "thingslink/command_journal.hpp"
#include "nvs_flash.h"

namespace thingslink {
bool NvsRuntime::initialize() {
    if (faulted_) return false;
    if (initialized_) return true;
    const auto result = nvs_flash_init_partition(partition);
    if (result != ESP_OK) { fault(result); return false; }
    initialized_ = true;
    return true;
}
NvsSnapshotStore::NvsSnapshotStore(NvsRuntime& runtime, SnapshotKind kind) : runtime_(runtime) {
    switch (kind) {
        case SnapshotKind::Outbox: key_ = "outbox"; limit_ = Outbox::snapshot_capacity; owner_ = 1; break;
        case SnapshotKind::Commands: key_ = "commands"; limit_ = CommandJournal::snapshot_capacity; owner_ = 2; break;
    }
}
bool NvsSnapshotStore::open() {
    if (opened_ || !key_ || !runtime_.ready() || (runtime_.owners_ & owner_)) return false;
    const auto result = nvs_open_from_partition(NvsRuntime::partition, "sdk_runtime", NVS_READWRITE, &handle_);
    if (result != ESP_OK) { runtime_.fault(result); return false; }
    opened_ = true;
    runtime_.owners_ |= owner_;
    return true;
}
void NvsSnapshotStore::close() {
    if (opened_) {
        nvs_close(handle_);
        runtime_.owners_ &= ~owner_;
        opened_ = false; handle_ = 0;
    }
}
ReadResult NvsSnapshotStore::read(std::uint8_t* buffer, std::size_t capacity, std::size_t& size) {
    size = 0;
    if (!usable() || !buffer || capacity == 0) return ReadResult::Failed;
    std::size_t stored = 0;
    auto result = nvs_get_blob(handle_, key_, nullptr, &stored);
    if (result == ESP_ERR_NVS_NOT_FOUND) return ReadResult::Missing;
    if (result != ESP_OK) { runtime_.fault(result); return ReadResult::Failed; }
    if (stored == 0 || stored > limit_ || stored > capacity) {
        runtime_.fault(ESP_ERR_NVS_INVALID_LENGTH); return ReadResult::Failed;
    }
    auto actual = stored;
    result = nvs_get_blob(handle_, key_, buffer, &actual);
    if (result != ESP_OK || actual != stored) {
        runtime_.fault(result == ESP_OK ? ESP_ERR_NVS_INVALID_LENGTH : result);
        return ReadResult::Failed;
    }
    size = actual;
    return ReadResult::Found;
}
bool NvsSnapshotStore::replace(const std::uint8_t* buffer, std::size_t size) {
    if (!usable() || !buffer || size == 0 || size > limit_) return false;
    auto result = nvs_set_blob(handle_, key_, buffer, size);
    if (result == ESP_OK) result = nvs_commit(handle_);
    if (result != ESP_OK) {
        // REMOVE_FAILED may have written a new value already. Even close/open is
        // insufficient: the vendor requires reinitialization to finish recovery.
        runtime_.fault(result); return false;
    }
    return true;
}
}
