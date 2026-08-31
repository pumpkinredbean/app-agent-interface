package io.github.pumpkinredbean.appagent.example.weather;

final class WeatherToolException extends Exception {
    final String code;
    final boolean retryable;

    WeatherToolException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    WeatherToolException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }
}
