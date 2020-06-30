package com.kapoorlabs.kiarademo.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.springframework.stereotype.Repository;

import com.kapoorlabs.kiara.adapters.PojoAdapter;
import com.kapoorlabs.kiara.domain.Store;
import com.kapoorlabs.kiara.exception.LoadDataException;
import com.kapoorlabs.kiara.loader.StoreLoader;
import com.kapoorlabs.kiarademo.domain.Movie;
import com.opencsv.CSVReader;

import lombok.Getter;

@Repository
public class MovieStore {

	
	@Getter
	Store movieStore;

	public MovieStore() {
		movieStore = new Store(PojoAdapter.getSdqlColumns(Movie.class));
		StoreLoader storeLoader = new StoreLoader(movieStore);

		ClassLoader classLoader = MovieStore.class.getClassLoader();
		InputStream csvFile = classLoader.getResourceAsStream("movie_metadata.csv");
		CSVReader csvReader = null;

		try {

			csvReader = new CSVReader(new InputStreamReader(csvFile));

			String[] words = null;

			csvReader.readNext();
			while ((words = csvReader.readNext()) != null) {
				Movie movie = new Movie();
				
				movie.setColor(getString(words[0]));
				movie.setDirector_name(getString(words[1]));
				movie.setNum_critic_for_reviews(getLong(words[2]));
				movie.setDuration(getInteger(words[3]));
				movie.setDirector_facebook_likes(getLong(words[4]));
				movie.setGross(getLong(words[5]));
				movie.setGenres(getString(words[6]));
				movie.setActors(getString(words[7]));
				movie.setMovie_title(getString(words[8]));
				movie.setNum_voted_users(getLong(words[9]));
				movie.setCast_total_facebook_likes(getLong(words[10]));
				movie.setFace_number_in_poster(getInteger(words[11]));
				movie.setPlot_keywords(getString(words[12]));
				movie.setMovie_imdb_link(getString(words[13]));
				movie.setNum_user_for_reviews(getLong(words[14]));
				movie.setLanguage(getString(words[15]));
				movie.setCountry(getString(words[16]));
				movie.setContent_rating(getString(words[17]));
				movie.setBudget(getLong(words[18]));
				movie.setTitle_year(getInteger(words[19]));
				movie.setImdb_score(getDouble(words[20]));
				movie.setAspect_ratio(getDouble(words[21]));
				movie.setMovie_facebook_likes(getLong(words[22]));

				try {
					storeLoader.loadTable(movie);
				} catch (LoadDataException ex) {
					continue;
				}

			}

			storeLoader.prepareForSearch();

		} catch (IOException e) {
			throw new RuntimeException();
		} finally {
			if (csvReader != null) {
				try {
					csvReader.close();
				} catch (IOException e) {
					throw new RuntimeException();
				}
			}
		}
		
	}
	
	private String getString(String inp) {
		return inp.isEmpty() || inp.equalsIgnoreCase("null") ? null : inp;
	}
	
	private Integer getInteger(String inp) {
		return inp.isEmpty() || inp.equalsIgnoreCase("null") ? null : Integer.parseInt(inp);
	}
	
	private Long getLong(String inp) {
		return inp.isEmpty() || inp.equalsIgnoreCase("null") ? null : Long.parseLong(inp);
	}
	
	private Double getDouble(String inp) {
		return inp.isEmpty() || inp.equalsIgnoreCase("null") ? null : Double.parseDouble(inp);
	}

}
