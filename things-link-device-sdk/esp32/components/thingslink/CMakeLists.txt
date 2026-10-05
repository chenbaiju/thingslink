set(SDK_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
idf_component_register(
    SRCS "${SDK_ROOT}/src/protocol.cpp" "${SDK_ROOT}/src/outbox.cpp" "${SDK_ROOT}/src/command_journal.cpp"
         "${SDK_ROOT}/src/mqtt_frames.cpp"
         "${SDK_ROOT}/src/connection.cpp" "${SDK_ROOT}/src/esp_mqtt_connection.cpp"
         "${SDK_ROOT}/src/nvs_snapshot.cpp"
         "${SDK_ROOT}/src/delivery_pump.cpp"
         "${SDK_ROOT}/src/settings.cpp" "${SDK_ROOT}/src/protected_config.cpp"
         "${SDK_ROOT}/src/example_logic.cpp" "${SDK_ROOT}/src/esp_wifi_station.cpp"
         "${SDK_ROOT}/.local/deps/cjson/cJSON.c"
    INCLUDE_DIRS "${SDK_ROOT}/src"
    PRIV_INCLUDE_DIRS "${SDK_ROOT}/.local/deps/cjson" "${SDK_ROOT}/.local/esp-mqtt/lib/include"
    REQUIRES esp-mqtt nvs_flash bootloader_support esp_wifi esp_netif)
target_compile_definitions(${COMPONENT_LIB} PRIVATE CJSON_NESTING_LIMIT=16)
target_compile_options(${COMPONENT_LIB} PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:-std=gnu++17;-fno-exceptions;-fno-rtti;-Wall;-Wextra;-Werror;-fstack-usage;-Wframe-larger-than=2048>")
