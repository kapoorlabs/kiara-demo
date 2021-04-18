package com.kapoorlabs.kiarademo.domain;

import com.kapoorlabs.kiara.domain.annotations.CaseInsensitive;
import com.kapoorlabs.kiara.domain.annotations.CommaSeperatedStrings;
import com.kapoorlabs.kiara.domain.annotations.OneEditAway;
import com.kapoorlabs.kiara.domain.annotations.StemmedIndex;

import lombok.Data;

@Data
public class Movie {
	
	@CaseInsensitive
	@CommaSeperatedStrings
	@OneEditAway
	private String actors;
	
	private String color;
	
	@CaseInsensitive
	@OneEditAway
	private String director_name;
	
	private Long num_critic_for_reviews;
	
	private Integer duration;
	
	private Long director_facebook_likes;
	
	private Long gross;
	
	@CaseInsensitive
	@CommaSeperatedStrings
	@OneEditAway
	@StemmedIndex
	private String genres;
	
	private String movie_title;
	
	private Long num_voted_users;
	
	private Long cast_total_facebook_likes;
	
	private Integer face_number_in_poster;
	
	@CommaSeperatedStrings
	@CaseInsensitive
	@OneEditAway
	@StemmedIndex
	private String plot_keywords;
	
	private String movie_imdb_link;
	
	private Long num_user_for_reviews;
	
	private String language;
	
	private String country;
	
	private String content_rating;
	
	private Long budget;
	
	private Integer title_year;
	
	private Double imdb_score;
	
	private Double aspect_ratio;
	
	private Long movie_facebook_likes;

}
