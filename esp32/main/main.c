#include <string.h>
#include <stdio.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_err.h"
#include "nvs_flash.h"

#include "config.h"
#include "wifi_manager.h"
#include "ble_server.h"
#include "csi_collector.h"

static const char *TAG = "main";

/* ---- CSI transmission task ----------------------------------------------- */

/**
 * Reads the latest CSI packet and pushes it to the BLE client at
 * CSI_SAMPLE_RATE_HZ.  The task self-regulates via vTaskDelayUntil so the
 * loop period stays accurate even when the BLE stack introduces small jitter.
 */
static void csi_tx_task(void *arg)
{
    csi_packet_t pkt;
    TickType_t   last_wake = xTaskGetTickCount();

    ESP_LOGI(TAG, "CSI TX task started (rate=%d Hz, packet=%d bytes)",
             CSI_SAMPLE_RATE_HZ, CSI_PACKET_TOTAL_BYTES);

    while (true) {
        /* Wait for next sample slot */
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(CSI_SAMPLE_INTERVAL_MS));

        if (!ble_server_is_connected()) {
            /* No client – nothing to send, keep spinning */
            continue;
        }

        esp_err_t err = csi_collector_get_latest_packet(
                            &pkt, CSI_SAMPLE_INTERVAL_MS);

        if (err == ESP_ERR_TIMEOUT) {
            ESP_LOGD(TAG, "CSI timeout – no new frame in %d ms",
                     CSI_SAMPLE_INTERVAL_MS);
            continue;
        }

        if (err != ESP_OK) {
            ESP_LOGE(TAG, "csi_collector_get_latest_packet error: %s",
                     esp_err_to_name(err));
            continue;
        }

        err = ble_server_send_csi_notification(
                  (const uint8_t *)&pkt, sizeof(pkt));
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "ble_server_send_csi_notification error: %s",
                     esp_err_to_name(err));
        }
    }
}

/* ---- app_main ------------------------------------------------------------ */

void app_main(void)
{
    esp_err_t err;

    ESP_LOGI(TAG, "WaveGuard ESP32 firmware starting");

    /* ------------------------------------------------------------------ */
    /* 1. NVS                                                              */
    /* ------------------------------------------------------------------ */
    err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES ||
        err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS partition worn out, erasing…");
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    ESP_LOGI(TAG, "NVS initialised");

    /* ------------------------------------------------------------------ */
    /* 2. WiFi (AP+STA mode, channel WIFI_CHANNEL)                        */
    /* ------------------------------------------------------------------ */
    err = wifi_manager_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "wifi_manager_init failed: %s", esp_err_to_name(err));
        ESP_ERROR_CHECK(err);
    }
    ESP_LOGI(TAG, "WiFi manager initialised");

    /* ------------------------------------------------------------------ */
    /* 3. BLE GATT server                                                  */
    /* ------------------------------------------------------------------ */
    err = ble_server_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "ble_server_init failed: %s", esp_err_to_name(err));
        ESP_ERROR_CHECK(err);
    }
    ESP_LOGI(TAG, "BLE server initialised");

    /* ------------------------------------------------------------------ */
    /* 4. CSI collector                                                    */
    /* ------------------------------------------------------------------ */
    err = csi_collector_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "csi_collector_init failed: %s", esp_err_to_name(err));
        ESP_ERROR_CHECK(err);
    }

    err = csi_collector_start();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "csi_collector_start failed: %s", esp_err_to_name(err));
        ESP_ERROR_CHECK(err);
    }
    ESP_LOGI(TAG, "CSI collector running");

    /* ------------------------------------------------------------------ */
    /* 5. Launch CSI→BLE transmission task                                */
    /* ------------------------------------------------------------------ */
    BaseType_t rc = xTaskCreatePinnedToCore(
        csi_tx_task,
        "csi_tx",
        4096,         /* stack size in words */
        NULL,
        5,            /* priority */
        NULL,
        1             /* pin to core 1; core 0 handles WiFi/BT */
    );

    if (rc != pdPASS) {
        ESP_LOGE(TAG, "Failed to create csi_tx_task");
        ESP_ERROR_CHECK(ESP_FAIL);
    }

    ESP_LOGI(TAG, "WaveGuard firmware running — device: %s, channel: %d",
             BLE_DEVICE_NAME, WIFI_CHANNEL);

    /*
     * app_main() returns here; the FreeRTOS scheduler continues running
     * the csi_tx_task and the BLE/WiFi system tasks.
     */
}
