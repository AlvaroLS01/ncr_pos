package com.comerzzia.ametller.pos.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ComerzziaPOSApplication {
	@SuppressWarnings("unused")
	private static final Logger log = LoggerFactory.getLogger(ComerzziaPOSApplication.class);

	public static void main(String[] args) {
		com.comerzzia.pos.core.gui.POSApplication.main(args);
	}

}
