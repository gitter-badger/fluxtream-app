package com.fluxtream.widgets.dataproviders;

import org.springframework.beans.factory.annotation.Autowired;

import com.fluxtream.TimeInterval;
import com.fluxtream.domain.GuestSettings;
import com.fluxtream.services.ApiDataService;
import com.fluxtream.services.GuestService;

import net.sf.json.JSONObject;

public abstract class AbstractWidgetDataProvider {

	@Autowired
	protected ApiDataService apiDataService;

	@Autowired
	protected GuestService guestService;

	public abstract void provideData(long guestId, GuestSettings settings,
			TimeInterval timeInterval, JSONObject o);

}