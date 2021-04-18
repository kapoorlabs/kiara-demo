package com.kapoorlabs.kiarademo.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.domain.KeywordSearchResult;
import com.kapoorlabs.kiara.search.KeywordSearch;
import com.kapoorlabs.kiara.search.StoreSearch;
import com.kapoorlabs.kiarademo.domain.Movie;
import com.kapoorlabs.kiarademo.dto.MovieResponse;
import com.kapoorlabs.kiarademo.repository.MovieStore;

@Service
public class MovieService {
	
	@Autowired
	MovieStore movieStore;
	
	private static final int MAX_KEYWORDS = 10;
	
	private static final int RESULT_LIMIT = 100;
	
	private static final KeywordSearch keywordSearch = new KeywordSearch(MAX_KEYWORDS);
	
	
	public List<Map<String, String>> query(Set<String> filterSet, List<Condition> conditions) {
		
		StoreSearch storeSearch = new StoreSearch();
		
		// Either you can pass a filterSet and get a List of HashMap
		return storeSearch.query(movieStore.getMovieStore(), conditions, filterSet);
	}
	
	public List<Movie> query(List<Condition> conditions) {
		
		StoreSearch storeSearch = new StoreSearch();
		
		// Or you can skip filterSet and get entire POJO back in the list
		return storeSearch.query(movieStore.getMovieStore(), conditions);
	}
	
	public MovieResponse queryKeywords(Set<String> searchString) {
		
		KeywordSearchResult<Movie> movieResult = keywordSearch.getBestMatch(searchString, movieStore.getMovieStore(), RESULT_LIMIT);
		List<String> movieNames = movieResult.getResult().stream().map(movie -> movie.getMovie_title()).collect(Collectors.toList());
		
		return new MovieResponse(movieResult.getKeywords(), movieNames);
	
		
	}
	
	

}
