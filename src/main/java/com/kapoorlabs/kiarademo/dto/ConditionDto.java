package com.kapoorlabs.kiarademo.dto;

import java.util.List;

import com.kapoorlabs.kiara.domain.Operator;

import lombok.Data;

@Data
public class ConditionDto {
	
	private String fieldName;
	
	private Operator operator;
	
	private List<String> values;
	
	private String lowerValue;
	
	private String upperValue;
	
}
