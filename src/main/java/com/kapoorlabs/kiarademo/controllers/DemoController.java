package com.kapoorlabs.kiarademo.controllers;

import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiarademo.domain.Movie;
import com.kapoorlabs.kiarademo.dto.ApiRequest;
import com.kapoorlabs.kiarademo.dto.ApiResponse;
import com.kapoorlabs.kiarademo.dto.ConditionDto;
import com.kapoorlabs.kiarademo.service.MovieService;

@RestController
public class DemoController {

	@Autowired
	MovieService movieService;

	@PostMapping(path = "/queyAndGetSelectedMovieAttributes")
	public ApiResponse queyAndGetSelectedMovieAttributes(@RequestBody ApiRequest apiRequest) throws Exception {

		List<Condition> conditions = new LinkedList<>();

		if (apiRequest.getConditions() == null) {
			apiRequest.setConditions(new LinkedList<>());
		}

		for (ConditionDto conditionDto : apiRequest.getConditions()) {
			if (conditionDto.getValues() != null) {
				conditions.add(new Condition(conditionDto.getFieldName(), conditionDto.getOperator(),
						conditionDto.getValues()));
			} else {
				conditions.add(new Condition(conditionDto.getFieldName(), conditionDto.getOperator(),
						conditionDto.getLowerValue(), conditionDto.getUpperValue()));
			}

		}

		ApiResponse apiResponse = new ApiResponse();
		apiResponse.setResults(movieService.query(apiRequest.getFilterSet(), conditions));
		return apiResponse;

	}
	
	@PostMapping(path = "/queyAndGetAllMovieAttributes")
	public List<Movie> queyAndGetAllMovieAttributes(@RequestBody ApiRequest apiRequest) throws Exception {

		List<Condition> conditions = new LinkedList<>();

		if (apiRequest.getConditions() == null) {
			apiRequest.setConditions(new LinkedList<>());
		}

		for (ConditionDto conditionDto : apiRequest.getConditions()) {
			if (conditionDto.getValues() != null) {
				conditions.add(new Condition(conditionDto.getFieldName(), conditionDto.getOperator(),
						conditionDto.getValues()));
			} else {
				conditions.add(new Condition(conditionDto.getFieldName(), conditionDto.getOperator(),
						conditionDto.getLowerValue(), conditionDto.getUpperValue()));
			}

		}


		return movieService.query(conditions);
		

	}

}
