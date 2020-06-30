package com.kapoorlabs.kiarademo.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.search.StoreSearch;
import com.kapoorlabs.kiarademo.repository.MovieStore;

@Service
public class MovieService {
	
	@Autowired
	MovieStore movieStore;
	
	
	public List<Map<String, String>> query(Set<String> filterSet, List<Condition> conditions) {
		
		StoreSearch storeSearch = new StoreSearch();
		
		return storeSearch.query(movieStore.getMovieStore(), conditions, filterSet);
	}
	
	

}
