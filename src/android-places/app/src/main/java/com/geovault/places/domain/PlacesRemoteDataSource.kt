package com.geovault.places.domain

import com.geovault.places.model.Place

interface PlacesRemoteDataSource {
    suspend fun fetchPlaces(): List<Place>
    suspend fun fetchPlace(id: Int): Place
    suspend fun createPlace(place: Place): Place
    suspend fun updatePlace(id: Int, place: Place): Place
    suspend fun deletePlace(id: Int)
}
