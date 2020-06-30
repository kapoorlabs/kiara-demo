package com.kapoorlabs.kiarademo.exceptionHandler;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.kapoorlabs.kiarademo.dto.ErrorResponse;

@RestController
@ControllerAdvice
public class CentralExceptionHandler extends ResponseEntityExceptionHandler {
	

	  @ExceptionHandler(Exception.class)
	  public final ResponseEntity<Object> handleAllExceptions(
	      final Exception ex, final WebRequest request) {

		    ErrorResponse errorResponse = new ErrorResponse();
		    errorResponse.setErrorCode(HttpStatus.BAD_REQUEST.value());
		    errorResponse.setDescription(ex.getMessage());

	    return new ResponseEntity<Object>(errorResponse, HttpStatus.BAD_REQUEST);
	  }


	  @Override
	  public ResponseEntity<Object> handleHttpMessageNotReadable(
	      final HttpMessageNotReadableException ex,
	      final HttpHeaders headers,
	      final HttpStatus status,
	      final WebRequest request) {

	    ErrorResponse errorResponse = new ErrorResponse();
	    errorResponse.setErrorCode(HttpStatus.BAD_REQUEST.value());
	    errorResponse.setDescription(ex.getMessage());
	    return new ResponseEntity<Object>(errorResponse, HttpStatus.BAD_REQUEST);
	  }

}


