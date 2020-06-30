package com.kapoorlabs.kiarademo.dto;

import lombok.Data;

@Data
public class ErrorResponse {
	private int errorCode;
	private String description;
}
