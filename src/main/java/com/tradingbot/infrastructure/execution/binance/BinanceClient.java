package com.tradingbot.infrastructure.execution.binance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Клиент для взаимодействия с API Binance.
 *
 * <p>Отвечает за:
 * <ul>
 *     <li>HTTP-взаимодействие с Binance API</li>
 *     <li>Подпись запросов (HMAC-SHA256)</li>
 *     <li>Синхронизацию времени с сервером Binance</li>
 *     <li>Формирование signed/unsigned запросов</li>
 * </ul>
 */
@Slf4j
@Component
public class BinanceClient {

    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private long timeOffset = 0;

    /**
     * Создаёт клиент Binance API.
     *
     * @param apiKey    публичный API ключ Binance
     * @param secretKey  секретный ключ Binance
     * @param baseUrl   базовый URL API (по умолчанию https://api.binance.com)
     */
    public BinanceClient(
            @Value("${binance.api-key}") String apiKey,
            @Value("${binance.secret-key}") String secretKey,
            @Value("${binance.base-url:https://api.binance.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Синхронизирует локальное время с сервером Binance.
     *
     * <p>Используется для корректной подписи запросов, зависящих от timestamp.
     */
    public void syncTime() {
        try {
            Map<String, Object> response = restTemplate.getForObject(baseUrl + "/api/v3/time", Map.class);
            long serverTime = ((Number) response.get("serverTime")).longValue();
            this.timeOffset = serverTime - System.currentTimeMillis();
            log.info("Binance time synced. Offset: {}ms", timeOffset);
        } catch (Exception e) {
            log.error("Failed to sync time with Binance", e);
        }
    }

    /**
     * Формирует HMAC-SHA256 подпись для параметров запроса.
     *
     * @param params параметры запроса
     * @return строка подписи
     */
    public String sign(Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return hmacSha256(query, secretKey);
    }

    /**
     * Внутренний метод вычисления HMAC-SHA256.
     *
     * @param data   строка данных
     * @param secret секретный ключ
     * @return hex-подпись
     */
    private String hmacSha256(String data, String secret) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hmac-sha256", e);
        }
    }

    /**
     * Выполняет GET-запрос к Binance API.
     *
     * @param path         endpoint (например /api/v3/order)
     * @param params       параметры запроса
     * @param responseType тип ответа
     * @param signed       требуется ли подпись
     * @param <T>          тип результата
     * @return ответ API
     */
    public <T> T get(String path, Map<String, String> params, Class<T> responseType, boolean signed) {
        String url = buildUrl(path, params, signed);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, responseType).getBody();
    }

    /**
     * Выполняет POST-запрос к Binance API.
     *
     * @param path         endpoint
     * @param params       параметры запроса
     * @param responseType тип ответа
     * @param signed       требуется ли подпись
     * @param <T>          тип результата
     * @return ответ API
     */
    public <T> T post(String path, Map<String, String> params, Class<T> responseType, boolean signed) {
        String url = buildUrl(path, params, signed);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, responseType).getBody();
    }

    /**
     * Формирует полный URL запроса с параметрами и подписью (если требуется).
     *
     * @param path   endpoint
     * @param params параметры запроса
     * @param signed требуется ли подпись
     * @return готовый URL
     */
    private String buildUrl(String path, Map<String, String> params, boolean signed) {
        Map<String, String> allParams = new TreeMap<>(params);
        if (signed) {
            allParams.put("timestamp", String.valueOf(getServerTime()));
            String signature = sign(allParams);
            allParams.put("signature", signature);
        }
        String query = allParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return baseUrl + path + (query.isEmpty() ? "" : "?" + query);
    }

    /**
     * Возвращает текущее серверное время Binance с учётом смещения.
     *
     * @return время в миллисекундах
     */
    public long getServerTime() {
        return System.currentTimeMillis() + timeOffset;
    }

    /**
     * Возвращает API ключ.
     *
     * @return API ключ
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Возвращает базовый URL API Binance.
     *
     * @return базовый URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Получает информацию об аккаунте Binance (балансы, лимиты и т.д.).
     *
     * @return карта данных аккаунта
     */
    public Map getAccountInfo() {
        return get("/api/v3/account", new HashMap<>(), Map.class, true);
    }
}