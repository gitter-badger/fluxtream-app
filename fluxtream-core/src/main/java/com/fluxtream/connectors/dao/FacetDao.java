package com.fluxtream.connectors.dao;

import java.util.List;
import com.fluxtream.TimeInterval;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.ObjectType;
import com.fluxtream.domain.AbstractFacet;

public interface FacetDao {

    public List<AbstractFacet> getFacetsByDates(Connector connector, long guestId, ObjectType objectType, List<String> dates);

    public List<AbstractFacet> getFacetsBetween(Connector connector, long guestId, ObjectType objectType, TimeInterval timeInterval);

    public AbstractFacet getOldestFacet(Connector connector, long guestId, ObjectType objectType);

    public AbstractFacet getLatestFacet(Connector connector, long guestId, ObjectType objectType);

    List<AbstractFacet> getFacetsBefore(long guestId, Connector connector, ObjectType objectType, long timeInMillis, int desiredCount);

    List<AbstractFacet> getFacetsAfter(long guestId, Connector connector, ObjectType objectType, long timeInMillis, int desiredCount);

    public void deleteAllFacets(Connector connector, long guestId);

    public void deleteAllFacets(Connector connector, ObjectType objectType, long guestId);

    public void persist(Object o);

    public void merge(Object o);
}
