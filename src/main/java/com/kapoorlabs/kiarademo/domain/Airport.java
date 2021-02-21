package com.kapoorlabs.kiarademo.domain;

import com.kapoorlabs.kiara.domain.annotations.CaseInsensitive;
import com.kapoorlabs.kiara.domain.annotations.OneEditAway;

import lombok.Data;

@Data
public class Airport {
	
	@CaseInsensitive
	@OneEditAway
	private String airportCode;
	
	@OneEditAway
	@CaseInsensitive
	private String airportName;
	
	@OneEditAway
	@CaseInsensitive
	private String countryName;

}
