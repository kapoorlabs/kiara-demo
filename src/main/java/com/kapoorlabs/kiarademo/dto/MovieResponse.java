package com.kapoorlabs.kiarademo.dto;

import java.util.List;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class MovieResponse {
	
	Set<String> bestKeywords;
	
	List<String> movies;

}
