package com.kapoorlabs.kiarademo.service;

import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kapoorlabs.kiara.domain.KeywordSearchResult;
import com.kapoorlabs.kiara.search.KeywordSearch;
import com.kapoorlabs.kiara.util.SpellCheckUtil;
import com.kapoorlabs.kiarademo.domain.Airport;
import com.kapoorlabs.kiarademo.repository.AirportStore;

@Service
public class AirportService {

	@Autowired
	private AirportStore airportStore;

	private final int MAX_KEYWORDS_TO_CONSIDER = 10;

	private KeywordSearch keywordSearch = new KeywordSearch(MAX_KEYWORDS_TO_CONSIDER);

	public List<Airport> query(String searchString, Integer topNMatches) {

		KeywordSearchResult<Airport> keywordSearchResult = keywordSearch.getBestMatch(searchString,
				airportStore.getAirportStore(), topNMatches);

		return keywordSearchResult.getResult();

	}

	public List<String> getTextPredictions(String searchString, Integer topNMatches) {

		List<String> predictions = new LinkedList<>();
		
		searchString = searchString.toLowerCase();

		predictions = SpellCheckUtil.getTextPredictions(searchString,
				airportStore.getAirportStore().getSpellCheckTrie(), topNMatches);

		if (predictions.isEmpty()) {
			String oneEditAwayWord = SpellCheckUtil.getOneEditKeyword(searchString,
					airportStore.getAirportStore().getSpellCheckTrie());
			predictions.add(oneEditAwayWord);

		}
		return predictions;

	}

}
