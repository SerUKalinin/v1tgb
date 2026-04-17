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
 */
@Slf4j
@Component
public class BinanceClient {

    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private long timeOffset = 0;

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
     * Синхронизирует локальное время с временем сервера Binance.
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
     * Подписывает параметры запроса.
     *
     * @param params параметры запроса
     * @return подпись HMAC-SHA256
     */
    public String sign(Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return hmacSha256(query, secretKey);
    }

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
     * Выполняет GET-запрос к API Binance.
     *
     * @param path         путь запроса
     * @param params       параметры запроса
     * @param responseType класс ответа
     * @param signed       требуется ли подпись
     * @param <T>          тип ответа
     * @return ответ от API
     */
    public <T> T get(String path, Map<String, String> params, Class<T> responseType, boolean signed) {
        String url = buildUrl(path, params, signed);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, responseType).getBody();
    }

    /**
     * Выполняет POST-запрос к API Binance.
     *
     * @param path         путь запроса
     * @param params       параметры запроса
     * @param responseType класс ответа
     * @param signed       требуется ли подпись
     * @param <T>          тип ответа
     * @return ответ от API
     */
    public <T> T post(String path, Map<String, String> params, Class<T> responseType, boolean signed) {
        String url = buildUrl(path, params, signed);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, responseType).getBody();
    }

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
     * Возвращает текущее время сервера Binance с учётом смещения.
     *
     * @return время сервера в миллисекундах
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
     * Получает информацию об аккаунте (балансы).
     */
    public Map getAccountInfo() {
        return get("/api/v3/account", new HashMap<>(), Map.class, true);
    }
}