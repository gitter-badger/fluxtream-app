package com.fluxtream.thirdparty.helpers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.fluxtream.Configuration;
import com.fluxtream.domain.metadata.DayMetadataFacet;
import com.fluxtream.domain.metadata.WeatherInfo;
import org.apache.commons.httpclient.HttpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WWOHelper {

	@Autowired
	private Configuration env;


    public void setWeatherInfo(DayMetadataFacet info,
                                List<WeatherInfo> weatherInfo) {
        if (weatherInfo.size() == 0)
            return;

        for (WeatherInfo weather : weatherInfo) {
            if (weather.tempC < info.minTempC)
                info.minTempC = weather.tempC;
            if (weather.tempF < info.minTempF)
                info.minTempF = weather.tempF;
            if (weather.tempC > info.maxTempC)
                info.maxTempC = weather.tempC;
            if (weather.tempF > info.maxTempF)
                info.maxTempF = weather.tempF;
        }

    }

    public List<WeatherInfo> getWeatherInfo(double latitude, double longitude, String fdate) throws HttpException, IOException {
        List<WeatherInfo> weather = new ArrayList<WeatherInfo>();
        //disabling for now
//		String wwoUrl = "http://www.worldweatheronline.com/feed/premium-weather-v2.ashx?" +
//"key=" + env.get("wwo.key") + "&feedkey=" + env.get("wwo.feedkey") + "&format=json&q=" + latitude + "," + longitude + "&date=" + fdate;
//		String wwoJson = fetch(wwoUrl);
//
//		JSONObject wwoInfo = JSONObject.fromObject(wwoJson);
//		if (wwoInfo!=null) {
//			JSONObject data = wwoInfo.getJSONObject("data");
//			if (data==null) return weather;
//			JSONArray weatherDataArray = data.getJSONArray("weather");
//			if (weatherDataArray==null) return weather;
//			JSONObject weatherData = weatherDataArray.getJSONObject(0);
//			if (weatherData==null) return weather;
//			JSONArray hourly = weatherData.getJSONArray("hourly");
//			if (hourly!=null) {
//				@SuppressWarnings("rawtypes")
//				Iterator iterator = hourly.iterator();
//				while (iterator.hasNext()) {
//					JSONObject hourlyRecord = (JSONObject) iterator.next();
//					WeatherInfo weatherInfo = new WeatherInfo();
//					weatherInfo.cloudcover = Integer.valueOf(hourlyRecord.getString("cloudcover"));
//					weatherInfo.humidity = Integer.valueOf(hourlyRecord.getString("humidity"));
//					weatherInfo.precipMM = Float.valueOf(hourlyRecord.getString("precipMM"));
//					weatherInfo.pressure = Integer.valueOf(hourlyRecord.getString("pressure"));
//					weatherInfo.tempC = Integer.valueOf(hourlyRecord.getString("tempC"));
//					weatherInfo.tempF = Integer.valueOf(hourlyRecord.getString("tempF"));
//					weatherInfo.minuteOfDay = Integer.valueOf(hourlyRecord.getString("time"));
//
//					weatherInfo.visibility = Integer.valueOf(hourlyRecord.getString("visibility"));
//					weatherInfo.weatherCode = Integer.valueOf(hourlyRecord.getString("weatherCode"));
//					JSONArray weatherDesc = hourlyRecord.getJSONArray("weatherDesc");
//					JSONArray weatherIconUrl = hourlyRecord.getJSONArray("weatherIconUrl");
//					weatherInfo.weatherDesc = weatherDesc.getJSONObject(0).getString("value");
//                    weatherInfo.weatherIconUrl = null;
//                    weatherInfo.weatherIconUrlDay = null;
//                    weatherInfo.weatherIconUrlNight = null;
//                    weatherInfo.weatherIconUrl = weatherIconUrl.getJSONObject(0).getString("value");
//					weatherInfo.winddirDegree = Integer.valueOf(hourlyRecord.getString("winddirDegree"));
//					weatherInfo.windspeedMiles = Integer.valueOf(hourlyRecord.getString("windspeedMiles"));
//                    weatherInfo.windspeedKmph = Integer.valueOf(hourlyRecord.getString("windspeedKmph"));
//                    weatherInfo.winddir16Point = hourlyRecord.getString("winddir16Point");
//
//					weather.add(weatherInfo);
//				}
//			}
//		}
		return weather;
	}
}
