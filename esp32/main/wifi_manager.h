#pragma once

#include <stdint.h>
#include "esp_err.h"

/**
 * @brief Initialise WiFi in AP+STA mode.
 *
 * - AP  : provides a beacon stream on the configured channel so that the STA
 *         interface can collect CSI from self-transmitted / nearby frames.
 * - STA : starts in connected-to-nothing state; CSI collection does not
 *         require an active association with a remote AP.
 *
 * @return ESP_OK on success.
 */
esp_err_t wifi_manager_init(void);

/**
 * @brief Switch both AP and STA interfaces to @p channel.
 *
 * @param channel  802.11 channel number (1–13 for 2.4 GHz).
 * @return ESP_OK on success.
 */
esp_err_t wifi_manager_set_channel(uint8_t channel);

/**
 * @brief Return the currently active WiFi channel.
 */
uint8_t wifi_manager_get_channel(void);
