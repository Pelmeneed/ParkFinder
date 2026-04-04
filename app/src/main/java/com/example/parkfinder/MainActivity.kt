package com.example.parkfinder


import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationListener
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.atan2
import android.widget.Toast
import androidx.appcompat.app.AlertDialog



class MainActivity : AppCompatActivity(), OnMapReadyCallback, LocationListener {
    lateinit var mMap: GoogleMap
    private lateinit var locationManager: LocationManager
    private var isFirstLocationUpdate = true
    private var currentUserLocation: Location? = null

    private var currentRadius = 250.0 // радиус по умолчанию 250 метров
    private lateinit var tvSelectedRadius: TextView

    private val radiusOptions = listOf(
        "50 м" to 50.0,
        "100 м" to 100.0,
        "250 м" to 250.0,
        "500 м" to 500.0,
        "750 м" to 750.0,
        "1 км" to 1000.0
    )

        private val androidLocationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            this@MainActivity.onLocationChanged(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("ServiceCast")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val btnSelectRadius = findViewById<Button>(R.id.btnSelectRadius)
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        tvSelectedRadius = findViewById(R.id.tvSelectedRadius)

        updateRadiusText()

        btnSelectRadius.setOnClickListener {
            showRadiusSelectionDialog()
        }

        btnSearch.setOnClickListener {
            searchParkingSpots()
        }

        var mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        mMap.uiSettings.apply {
            mMap.isBuildingsEnabled = true
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
        }
        val nizhniyTagil = LatLng(57.922773702244136, 59.98502957359892)
        mMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(nizhniyTagil, 12f)
        )
        enableMyLocation()
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(ACCESS_FINE_LOCATION), 1)
        }
    }

    private var locationRequestStartTime: Long = 0

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 10f, androidLocationListener)
        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER, 500, 10f, androidLocationListener)
    }

    override fun onLocationChanged(location: Location) {

        if (locationRequestStartTime > 0) {
            val elapsed = System.currentTimeMillis() - locationRequestStartTime
            Log.d("firstLocation", "Время на обнаружение первого местоположения: $elapsed мс")
            locationRequestStartTime = 0
        }

        currentUserLocation = location
        if (isFirstLocationUpdate) {
            val userLocation = LatLng(location.latitude, location.longitude)
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 17f))
            isFirstLocationUpdate = false

            displayParkingSpots()

            Toast.makeText(this, "Ваше местоположение определено. Радиус поиска: ${tvSelectedRadius.text}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mMap.isMyLocationEnabled = true
                startLocationUpdates()
            }
        }
    }

    private fun displayParkingSpots() {
        val startTime = System.currentTimeMillis()
        try {
            val userLocation = currentUserLocation
            if (userLocation == null) {
                return
            }

            val jsonString = resources.openRawResource(R.raw.parking_spots)
                .bufferedReader()
                .use { it.readText() }

            val gson = Gson()
            val listType = object : TypeToken<List<ParkingSpot>>() {}.type
            val allParkingSpots: List<ParkingSpot> = gson.fromJson(jsonString, listType)

            val sizeInDp = 16
            val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()

            val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.park_sign32p)
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, sizeInPx, sizeInPx, false)
            val customIcon = BitmapDescriptorFactory.fromBitmap(scaledBitmap)

            allParkingSpots.forEach { spot ->
                val distance = calculateDistance(
                    userLocation.latitude, userLocation.longitude,
                    spot.latitude, spot.longitude
                )

                if (distance <= currentRadius) {
                    val position = LatLng(spot.latitude, spot.longitude)
                    mMap.addMarker(
                        MarkerOptions()
                            .position(position)
                            .title(spot.name)
                            .icon(customIcon)
                            .anchor(0.5f, 1f)
                            .snippet("${String.format("%.1f", distance)} метров")
                    )
                }
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
        val duration = System.currentTimeMillis() - startTime
        Log.d("TimeToDisplay", "Функция displayParkingSpots() выполняется за $duration мс")
    }

    private fun showRadiusSelectionDialog() {
        val radiusNames = radiusOptions.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите радиус поиска")
            .setItems(radiusNames) { _, which ->
                currentRadius = radiusOptions[which].second
                updateRadiusText()
                Toast.makeText(this,
                    "Выбран радиус: ${radiusOptions[which].first}. Нажмите \"Найти\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun updateRadiusText() {
        val radiusText = when (currentRadius) {
            1000.0 -> "Радиус: 1 км"
            else -> "Радиус: ${currentRadius.toInt()} м"
        }
        tvSelectedRadius.text = radiusText
    }

    private fun searchParkingSpots() {
        if (currentUserLocation == null) {
            Toast.makeText(this, "Местоположение не определено. Проверьте включение геолокации...", Toast.LENGTH_SHORT).show()
            return
        }
        mMap.clear()

        if (ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        }
        displayParkingSpots()

        Toast.makeText(this, "Поиск парковок в радиусе ${tvSelectedRadius.text}", Toast.LENGTH_SHORT).show()
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // радиус Земли
        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)
        val a = sin(latDistance / 2) * sin(latDistance / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(lonDistance / 2) * sin(lonDistance / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}

