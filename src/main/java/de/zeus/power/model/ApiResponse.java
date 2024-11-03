package de.zeus.power.model;

import org.springframework.http.HttpStatus;

public record ApiResponse<T>(boolean success,
                             HttpStatus statusCode,
                             String message,
                             T data) {
}
