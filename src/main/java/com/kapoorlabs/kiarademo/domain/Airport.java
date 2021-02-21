package com.kapoorlabs.kiarademo.domain;

import com.kapoorlabs.kiara.domain.annotations.Predictable;

import lombok.Data;

@Data
public class Airport {
	
	@Predictable
	private String airportCode;
	
	@Predictable
	private String airportName;
	
	@Predictable
	private String countryName;

}
