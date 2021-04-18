package com.kapoorlabs.kiarademo.repository;

import org.springframework.stereotype.Repository;

import com.kapoorlabs.kiara.domain.Store;
import com.kapoorlabs.kiara.exception.LoadDataException;
import com.kapoorlabs.kiara.loader.StoreLoader;
import com.kapoorlabs.kiara.search.KeywordSearch;
import com.kapoorlabs.kiarademo.domain.Config;

import lombok.Getter;

@Repository
public class ConfigStore {

	@Getter
	Store<Config> configStore;

	public ConfigStore() throws LoadDataException {
		
		loadConfigStore();

	}

	private void loadConfigStore() throws LoadDataException
	{
		configStore = new Store<>(Config.class);
		StoreLoader<Config> storeLoader = new StoreLoader<>(configStore);
		
		storeLoader.loadTable(new Config("in", null, null, 2.00));
		storeLoader.loadTable(new Config("usa", null, null, 1.00));
		storeLoader.loadTable(new Config("usa", null, "dl", 3.00));
		storeLoader.loadTable(new Config("usa", "nyc", null, 2.50));
		storeLoader.loadTable(new Config("usa", "nyc", "aa", 4.50));
		storeLoader.loadTable(new Config("usa", "nyc", "dl", 5.50));
		
		storeLoader.prepareForSearch();
		
	}
	
	public static void main(String[] args) throws LoadDataException {
		
		ConfigStore configStore = new ConfigStore();
		
		KeywordSearch keywordSearch = new KeywordSearch(10); // <- 10 represents maximum keywords to search for, you can give any int.
		
		System.out.println(keywordSearch.getMinimumMatch("nyc dl usa", configStore.getConfigStore()));
		
		
	}

}
