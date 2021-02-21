package com.kapoorlabs.kiarademo.controllers;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.kapoorlabs.kiarademo.service.AirportService;

@RestController
public class AirportController {

	@Autowired
	AirportService airportService;

	@GetMapping(path = "/typeAhead/airport/{searchString}")
	public List<String> typeAheadAirport(@PathVariable String searchString) throws Exception {
		
		final int topNMatches = 10;

		return airportService.getTextPredictions(searchString, topNMatches);

	}


}
