package com.kapoorlabs.kiarademo.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.kapoorlabs.kiara.domain.Store;
import com.kapoorlabs.kiara.exception.LoadDataException;
import com.kapoorlabs.kiara.loader.StoreLoader;
import com.kapoorlabs.kiarademo.domain.Airport;
import com.opencsv.CSVReader;

import lombok.Getter;

@Repository
public class AirportStore {

	@Getter
	Store<Airport> airportStore;

	public AirportStore() {

		try {
			loadStore(getAirportData());
		} catch (LoadDataException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private void loadStore(List<Airport> airports) throws LoadDataException {

		airportStore = new Store<>(Airport.class);
		StoreLoader<Airport> storeLoader = new StoreLoader<>(airportStore);

		for (Airport airport : airports) {

			storeLoader.loadTable(airport);

		}

		storeLoader.prepareForSearch();

	}

	private List<Airport> getAirportData() {

		ClassLoader classLoader = AirportStore.class.getClassLoader();
		InputStream csvFile = classLoader.getResourceAsStream("airports.csv");
		CSVReader csvReader = null;

		List<Airport> airportList = new LinkedList<>();
		csvReader = new CSVReader(new InputStreamReader(csvFile));

		String[] words = null;

		try {
			csvReader.readNext();

			while ((words = csvReader.readNext()) != null) {
				Airport airport = new Airport();
				airport.setAirportName(words[0].toLowerCase());
				airport.setCountryName(words[1].toLowerCase());
				airport.setAirportCode(words[2].toLowerCase());

				airportList.add(airport);
			}
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

		return airportList;
	}

}
