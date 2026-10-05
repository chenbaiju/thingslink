idf_component_register(SRCS "${SDK_APP}.cpp" INCLUDE_DIRS "."
    REQUIRES thingslink esp_psram esp_system esp_driver_gpio esp_driver_usb_serial_jtag esp_timer)
target_compile_options(${COMPONENT_LIB} PRIVATE -std=gnu++17 -Wall -Wextra -Werror -fstack-usage -Wframe-larger-than=2048)
