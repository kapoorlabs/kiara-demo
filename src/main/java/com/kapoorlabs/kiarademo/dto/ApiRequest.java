package com.kapoorlabs.kiarademo.dto;

import java.util.List;
import java.util.Set;

import lombok.Data;

@Data
public class ApiRequest {
	
	private Set<String> filterSet;
	
	private List<ConditionDto> conditions;

}
