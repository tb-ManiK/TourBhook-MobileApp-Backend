package com.tourbhook.api.service.impl;

import com.tourbhook.api.dto.weather.WeatherResponse;
import com.tourbhook.api.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherServiceImpl implements WeatherService {

    private final GoogleMapsClient googleMapsClient;

    @Override
    public WeatherResponse getWeather(String city, LocalDate date) {
        Map<String, Object> geocode = googleMapsClient.geocodeCityRaw(city);

        String geoStatus = geocode.get("status") != null
                ? geocode.get("status").toString()
                : "null";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) geocode.get("results");

        if (!"OK".equalsIgnoreCase(geoStatus) || results == null || results.isEmpty()) {
            return new WeatherResponse(
                    city,
                    date,
                    "unknown",
                    0,
                    0,
                    "Location not found. Geocode status: " + geoStatus,
                    0,
                    "0 km/h",
                    0
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> geometry = (Map<String, Object>) results.get(0).get("geometry");

        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) geometry.get("location");

        double lat = ((Number) location.get("lat")).doubleValue();
        double lng = ((Number) location.get("lng")).doubleValue();

        log.info("Weather lookup lat={}, lng={}", lat, lng);

        Map<String, Object> weather = googleMapsClient.getCurrentWeather(lat, lng);
        log.info("Weather condition field: {}", weather.get("weatherCondition"));
        if (weather == null || weather.isEmpty()) {
            return new WeatherResponse(
                    city,
                    date,
                    "unknown",
                    0,
                    0,
                    "Weather API returned empty response or timed out",
                    0,
                    "0 km/h",
                    0
            );
        }

        String condition = extractCondition(weather);
        int currentTemp = extractTemperature(weather, "temperature");
        int minTemp = extractHistoryTemperature(weather, "minTemperature");
        int maxTemp = extractHistoryTemperature(weather, "maxTemperature");
        int humidity = extractInteger(weather, "relativeHumidity");
        int precipitationChance = extractPrecipitationProbability(weather);
        String wind = extractWind(weather);

        if (minTemp == 0 && maxTemp == 0 && currentTemp != 0) {
            minTemp = currentTemp;
            maxTemp = currentTemp;
        }

        return new WeatherResponse(
                city,
                date,
                condition,
                minTemp,
                maxTemp,
                "Live weather from Google Weather API",
                precipitationChance,
                wind,
                humidity
        );
    }



    private String extractCondition(Map<String, Object> weather) {
        Object value = weather.get("weatherCondition");

        if (value instanceof Map<?, ?> conditionMap) {
            Object description = conditionMap.get("description");

            if (description instanceof Map<?, ?> descriptionMap) {
                Object text = descriptionMap.get("text");

                if (text != null) {
                    return text.toString();
                }
            }

            Object type = conditionMap.get("type");
            if (type != null) {
                return type.toString();
            }
        }

        return "unknown";
    }



//    private String extractCondition(Map<String, Object> weather) {
//        Object value = weather.get("weatherCondition");
//        if (value instanceof Map<?, ?> map) {
//            Object text = map.get("text");
//            if (text != null) return text.toString();
//
//            Object description = map.get("description");
//            if (description != null) return description.toString();
//
//            Object type = map.get("type");
//            if (type != null) return type.toString();
//        }
//        return "unknown";
//    }

    private int extractTemperature(Map<String, Object> weather, String key) {
        Object value = weather.get(key);
        if (value instanceof Map<?, ?> map) {
            Object degrees = map.get("degrees");
            if (degrees instanceof Number number) {
                return (int) Math.round(number.doubleValue());
            }

            Object rawValue = map.get("value");
            if (rawValue instanceof Number number) {
                return (int) Math.round(number.doubleValue());
            }
        }
        return 0;
    }

    private int extractHistoryTemperature(Map<String, Object> weather, String key) {
        Object history = weather.get("currentConditionsHistory");
        if (history instanceof Map<?, ?> historyMap) {
            Object temp = historyMap.get(key);
            if (temp instanceof Map<?, ?> tempMap) {
                Object degrees = tempMap.get("degrees");
                if (degrees instanceof Number number) {
                    return (int) Math.round(number.doubleValue());
                }

                Object rawValue = tempMap.get("value");
                if (rawValue instanceof Number number) {
                    return (int) Math.round(number.doubleValue());
                }
            }
        }
        return 0;
    }

    private int extractInteger(Map<String, Object> weather, String key) {
        Object value = weather.get(key);
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        return 0;
    }

    private int extractPrecipitationProbability(Map<String, Object> weather) {
        Object precipitation = weather.get("precipitation");
        if (precipitation instanceof Map<?, ?> map) {
            Object probability = map.get("probability");
            if (probability instanceof Number number) {
                return (int) Math.round(number.doubleValue());
            }
        }
        return 0;
    }

    private String extractWind(Map<String, Object> weather) {
        Object windObj = weather.get("wind");
        if (windObj instanceof Map<?, ?> windMap) {
            Object speedObj = windMap.get("speed");
            if (speedObj instanceof Map<?, ?> speedMap) {
                Object value = speedMap.get("value");
                if (value instanceof Number number) {
                    return Math.round(number.doubleValue()) + " km/h";
                }
            }

            Object speed = windMap.get("speed");
            if (speed instanceof Number number) {
                return Math.round(number.doubleValue()) + " km/h";
            }
        }
        return "0 km/h";
    }
}