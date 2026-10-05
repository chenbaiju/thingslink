#pragma once
#include <array>
#include <cstdint>
#include <cstring>

namespace thingslink::detail {
template<std::size_t N> std::size_t length(const std::array<char, N>& s) {
    std::size_t n = 0;
    while (n < N && s[n]) ++n;
    return n;
}
inline bool clock_valid(std::uint64_t ms) { return ms >= 1577836800000ULL && ms <= 253402300799999ULL; }
inline std::uint32_t crc(const std::uint8_t* data, std::size_t size) {
    std::uint32_t result = 0xffffffffU;
    for (std::size_t i = 0; i < size; ++i) {
        result ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit)
            result = (result >> 1) ^ (0xedb88320U & (0U - (result & 1)));
    }
    return ~result;
}
struct Writer {
    std::uint8_t* data; std::size_t position{};
    void number(std::uint64_t n, unsigned bytes) {
        for (unsigned i = 0; i < bytes; ++i) data[position++] = static_cast<std::uint8_t>(n >> (i * 8));
    }
    void text(const char* value, std::size_t size) {
        std::memcpy(data + position, value, size); position += size;
    }
};
struct Reader {
    const std::uint8_t* data; std::size_t size, position{}; bool valid{true};
    std::uint64_t number(unsigned bytes) {
        if (bytes > size - position) { valid = false; return 0; }
        std::uint64_t result = 0;
        for (unsigned i = 0; i < bytes; ++i) result |= std::uint64_t(data[position++]) << (i * 8);
        return result;
    }
    template<std::size_t N> void text(std::array<char, N>& out, std::size_t count) {
        out.fill(0);
        if (count >= N || count > size - position) { valid = false; return; }
        for (std::size_t i = 0; i < count; ++i) {
            char c = static_cast<char>(data[position++]);
            if (!c) valid = false;
            out[i] = c;
        }
    }
};
}
