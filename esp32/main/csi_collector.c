#include "csi_collector.h"
#include "config.h"

#include <string.h>
#include <math.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_wifi_types.h"

static const char *TAG = "csi_collector";

/* Ring buffer of CSI packets */
static csi_packet_t  s_ring[CSI_RING_BUFFER_SIZE];
static volatile int  s_write_idx = 0;   /* next slot to write             */
static volatile int  s_read_idx  = 0;   /* oldest unread slot             */
static SemaphoreHandle_t s_data_ready;  /* signals a new frame is waiting */
static SemaphoreHandle_t s_ring_mutex;  /* protects ring-buffer indices   */
static volatile bool s_running = false;

/* ---- helpers ------------------------------------------------------------- */

/**
 * Convert a pair of int8_t imaginary/real CSI values to amplitude and phase.
 * ESP-IDF CSI data is stored as interleaved (imag, real) int8_t pairs.
 */
static inline void iq_to_amp_phase(int8_t imag, int8_t real,
                                   float *amp, float *phase)
{
    float fi = (float)imag;
    float fr = (float)real;
    *amp   = sqrtf(fi * fi + fr * fr);
    *phase = atan2f(fi, fr);
}

/* ---- CSI callback -------------------------------------------------------- */

static void csi_rx_callback(void *ctx, wifi_csi_info_t *info)
{
    if (!s_running || info == NULL || info->buf == NULL) {
        return;
    }

    /* The CSI buffer contains (len/2) complex samples stored as
     * pairs of int8_t: [imag0, real0, imag1, real1, ...].
     * We only use the first CSI_SUBCARRIER_NUM pairs.                       */
    int pairs = info->len / 2;
    if (pairs < CSI_SUBCARRIER_NUM) {
        ESP_LOGW(TAG, "CSI frame too short: %d pairs (need %d)",
                 pairs, CSI_SUBCARRIER_NUM);
        return;
    }

    csi_packet_t pkt;
    memset(&pkt, 0, sizeof(pkt));

    pkt.rssi        = (int32_t)info->rx_ctrl.rssi;
    pkt.noise_floor = (int32_t)info->rx_ctrl.noise_floor;
    pkt.channel     = (uint16_t)info->rx_ctrl.channel;
    pkt.bandwidth   = (uint16_t)(info->rx_ctrl.cwb ? 40 : 20);

    for (int i = 0; i < CSI_SUBCARRIER_NUM; i++) {
        int8_t imag = info->buf[2 * i];
        int8_t real = info->buf[2 * i + 1];
        iq_to_amp_phase(imag, real, &pkt.amplitude[i], &pkt.phase[i]);
    }

    /* Write into ring buffer */
    if (xSemaphoreTake(s_ring_mutex, 0) == pdTRUE) {
        s_ring[s_write_idx] = pkt;
        s_write_idx = (s_write_idx + 1) % CSI_RING_BUFFER_SIZE;
        /* If we lapped the reader, advance it to avoid stale data */
        if (s_write_idx == s_read_idx) {
            s_read_idx = (s_read_idx + 1) % CSI_RING_BUFFER_SIZE;
        }
        xSemaphoreGive(s_ring_mutex);
        xSemaphoreGive(s_data_ready);   /* signal consumer */
    }
}

/* ---- public API ---------------------------------------------------------- */

esp_err_t csi_collector_init(void)
{
    s_data_ready = xSemaphoreCreateBinary();
    if (s_data_ready == NULL) {
        return ESP_ERR_NO_MEM;
    }

    s_ring_mutex = xSemaphoreCreateMutex();
    if (s_ring_mutex == NULL) {
        vSemaphoreDelete(s_data_ready);
        return ESP_ERR_NO_MEM;
    }

    /* Configure the CSI receiver */
    wifi_csi_config_t csi_cfg = {
        .lltf_en           = true,
        .htltf_en          = true,
        .stbc_htltf2_en    = true,
        .ltf_merge_en      = true,
        .channel_filter_en = false,
        .manu_scale        = false,
        .shift             = 0,
    };

    esp_err_t err = esp_wifi_set_csi_config(&csi_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_csi_config failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_wifi_set_csi_rx_cb(csi_rx_callback, NULL);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_csi_rx_cb failed: %s", esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "CSI collector initialised (subcarriers=%d, rate=%dHz)",
             CSI_SUBCARRIER_NUM, CSI_SAMPLE_RATE_HZ);
    return ESP_OK;
}

esp_err_t csi_collector_start(void)
{
    s_running = true;

    esp_err_t err = esp_wifi_set_csi(true);
    if (err != ESP_OK) {
        s_running = false;
        ESP_LOGE(TAG, "esp_wifi_set_csi(true) failed: %s", esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "CSI collection started");
    return ESP_OK;
}

esp_err_t csi_collector_stop(void)
{
    s_running = false;

    esp_err_t err = esp_wifi_set_csi(false);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_csi(false) failed: %s", esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "CSI collection stopped");
    return ESP_OK;
}

esp_err_t csi_collector_get_latest_packet(csi_packet_t *out, uint32_t timeout_ms)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    /* Wait for a fresh frame */
    if (xSemaphoreTake(s_data_ready,
                       pdMS_TO_TICKS(timeout_ms)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    /* Copy the most recently written packet */
    if (xSemaphoreTake(s_ring_mutex, portMAX_DELAY) != pdTRUE) {
        return ESP_FAIL;
    }

    int latest = (s_write_idx - 1 + CSI_RING_BUFFER_SIZE) % CSI_RING_BUFFER_SIZE;
    *out = s_ring[latest];

    xSemaphoreGive(s_ring_mutex);
    return ESP_OK;
}
