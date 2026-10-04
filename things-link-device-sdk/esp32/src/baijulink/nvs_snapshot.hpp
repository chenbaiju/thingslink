#pragma once
#include "outbox.hpp"
#include "nvs.h"

namespace baijulink {
enum class SnapshotKind { Outbox, Commands };
// Exactly one runtime per boot, with a single worker owning its two stores.
// A failed init/I/O/commit poisons the whole runtime for the remainder of boot.
// Restart, then recover both cores; never reconstruct this object to clear a fault.
// This operational partition is NOT a protected credential store.
class NvsRuntime {
public:
    static constexpr const char* partition = "bl_state";
    NvsRuntime() = default;
    NvsRuntime(const NvsRuntime&) = delete;
    NvsRuntime& operator=(const NvsRuntime&) = delete;
    bool initialize();
    bool ready() const { return initialized_ && !faulted_; }
    esp_err_t last_error() const { return last_error_; }
private:
    friend class NvsSnapshotStore;
    bool initialized_{}, faulted_{};
    unsigned owners_{};
    esp_err_t last_error_{ESP_OK};
    void fault(esp_err_t error) { faulted_ = true; last_error_ = error; }
};
// Runtime outlives stores; stores outlive their Outbox/CommandJournal instances.
// No default partition, arbitrary namespace/key, erase, or auto-repair entry point.
class NvsSnapshotStore final : public SnapshotStore {
public:
    NvsSnapshotStore(NvsRuntime& runtime, SnapshotKind kind);
    ~NvsSnapshotStore() override { close(); }
    NvsSnapshotStore(const NvsSnapshotStore&) = delete;
    NvsSnapshotStore& operator=(const NvsSnapshotStore&) = delete;
    bool open();
    void close();
    ReadResult read(std::uint8_t*, std::size_t capacity, std::size_t& size) override;
    bool replace(const std::uint8_t*, std::size_t size) override;
private:
    NvsRuntime& runtime_;
    const char* key_{};
    std::size_t limit_{};
    unsigned owner_{};
    nvs_handle_t handle_{};
    bool opened_{};
    bool usable() const { return opened_ && runtime_.ready(); }
};
}
