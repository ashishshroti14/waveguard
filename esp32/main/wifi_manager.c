#include "wifi_manager.h"
#include "config.h"

#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "nvs_flash.h"

static const char *TAG = "wifi_manager";

static uint8_t s_current_channel = WIFI_CHANNEL;

/* ---- internal event handler ---------------------------------------------- */

static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data)
{
    if (event_base == WIFI_EVENT) {
        switch (event_id) {
        case WIFI_EVENT_AP_STACONNECTED: {
            wifi_event_ap_staconnected_t *e =
                (wifi_event_ap_staconnected_t *)event_data;
            ESP_LOGI(TAG, "AP: station " MACSTR " joined, AID=%d",
                     MAC2STR(e->mac), e->aid);
            break;
        }
        case WIFI_EVENT_AP_STADISCONNECTED: {
            wifi_event_ap_stadisconnected_t *e =
                (wifi_event_ap_stadisconnected_t *)event_data;
            ESP_LOGI(TAG, "AP: station " MACSTR " left, AID=%d",
                     MAC2STR(e->mac), e->aid);
            break;
        }
        case WIFI_EVENT_STA_START:
            ESP_LOGI(TAG, "STA started (channel %d)", s_current_channel);
            break;
        case WIFI_EVENT_STA_DISCONNECTED:
            /* STA is intentionally not associated; this is normal. */
            break;
        default:
            break;
        }
    }
}

/* ---- public API ---------------------------------------------------------- */

esp_err_t wifi_manager_init(void)
{
    esp_err_t err;

    /* Create the default event loop if it hasn't been created yet */
    err = esp_event_loop_create_default();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_event_loop_create_default failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    /* Initialise the TCP/IP stack */
    err = esp_netif_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_netif_init failed: %s", esp_err_to_name(err));
        return err;
    }

    /* Create default netif objects for AP and STA */
    esp_netif_create_default_wifi_ap();
    esp_netif_create_default_wifi_sta();

    /* Initialise the WiFi driver */
    wifi_init_config_t init_cfg = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&init_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_init failed: %s", esp_err_to_name(err));
        return err;
    }

    /* Register event handler */
    err = esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                              wifi_event_handler, NULL, NULL);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_event_handler_instance_register failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    /* Set AP+STA mode */
    err = esp_wifi_set_mode(WIFI_MODE_APSTA);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_mode failed: %s", esp_err_to_name(err));
        return err;
    }

    /* Configure AP */
    wifi_config_t ap_cfg = {
        .ap = {
            .ssid            = WIFI_AP_SSID,
            .ssid_len        = (uint8_t)strlen(WIFI_AP_SSID),
            .channel         = WIFI_CHANNEL,
            .password        = WIFI_AP_PASSWORD,
            .max_connection  = WIFI_AP_MAX_CONN,
            .authmode        = WIFI_AUTH_OPEN,
        },
    };
    err = esp_wifi_set_config(WIFI_IF_AP, &ap_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_config(AP) failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    /* Configure STA (no target network – only used as a CSI collector) */
    wifi_config_t sta_cfg = {
        .sta = {
            .ssid     = WIFI_STA_SSID,
            .password = WIFI_STA_PASSWORD,
            .channel  = WIFI_CHANNEL,
        },
    };
    err = esp_wifi_set_config(WIFI_IF_STA, &sta_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_config(STA) failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    /* Enable promiscuous-style CSI reception for self-transmitted frames */
    err = esp_wifi_set_promiscuous(false);   /* promiscuous OFF; CSI is separate */
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "esp_wifi_set_promiscuous failed: %s",
                 esp_err_to_name(err));
        /* non-fatal */
    }

    err = esp_wifi_start();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_start failed: %s", esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "WiFi started in AP+STA mode on channel %d", WIFI_CHANNEL);
    return ESP_OK;
}

esp_err_t wifi_manager_set_channel(uint8_t channel)
{
    if (channel < 1 || channel > 13) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_channel(%d) failed: %s",
                 channel, esp_err_to_name(err));
        return err;
    }

    s_current_channel = channel;
    ESP_LOGI(TAG, "WiFi channel set to %d", channel);
    return ESP_OK;
}

uint8_t wifi_manager_get_channel(void)
{
    return s_current_channel;
}
