package com.aicfo.feature.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.data.repository.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The vehicle screen's state holder (issue 10.4; §12, ARC-004, ARC-005).
 *
 * Why:  the screen collects three kinds of history and shows one prediction over them. Every figure
 *       it displays was computed by AI-VEH in `:domain:engines:vehicle`; this class does exactly
 *       one arithmetic thing — rupees typed by a person become paise (MNY-001) — and nothing else.
 *       No date arithmetic, no interval, no cost: those are the engine's, and duplicating one here
 *       would be the second definition this project keeps removing.
 * What: subscribes to the vehicles, holds the form fields, and forwards writes.
 * Result: a `StateFlow<VehiclesUiState>` the screen renders.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * Input:  [repository]; [clock] — only to default a date field to today (TIM-001).
 * Output: the view model.
 */
@HiltViewModel
class VehiclesViewModel
    @Inject
    constructor(
        private val repository: VehicleRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(VehiclesUiState(odometerDate = today(), serviceDate = today()))

        /** The screen's state. Input: none. Output: `StateFlow<VehiclesUiState>`. */
        val uiState: StateFlow<VehiclesUiState> = _uiState.asStateFlow()

        init {
            observeVehicles()
        }

        /**
         * Handles one event from the screen.
         * Result: the state moves, or a write runs and the subscription re-emits.
         * Input: [event]. Output: none.
         */
        fun onEvent(event: VehiclesEvent) {
            when (event) {
                VehiclesEvent.AddVehicle -> addVehicle()
                is VehiclesEvent.LogOdometer -> logOdometer(event.vehicleId)
                is VehiclesEvent.LogService -> logService(event.vehicleId)
                is VehiclesEvent.SetRenewal -> setRenewal(event)
                is VehiclesEvent.Remove -> run(repository::removeVehicle, event.vehicleId)
                else -> typed(event)
            }
        }

        /**
         * The events that only move a field.
         * Why:    split from [onEvent] so the branch that *writes* stays short enough to read —
         *         and so a new field cannot quietly push a write past a complexity threshold.
         * Result: the state moves. Input: [event]. Output: none.
         */
        private fun typed(event: VehiclesEvent) {
            when (event) {
                is VehiclesEvent.LabelChanged -> _uiState.update { it.copy(newLabel = event.label) }
                is VehiclesEvent.ClassChanged -> _uiState.update { it.copy(newClass = event.vehicleClass) }
                is VehiclesEvent.ToggleLog ->
                    _uiState.update {
                        it.copy(openVehicleId = if (it.openVehicleId == event.vehicleId) null else event.vehicleId)
                    }
                is VehiclesEvent.OdometerKmChanged -> _uiState.update { it.copy(odometerKm = digits(event.km)) }
                is VehiclesEvent.OdometerDateChanged -> _uiState.update { it.copy(odometerDate = event.isoDate) }
                is VehiclesEvent.ServiceCostChanged ->
                    _uiState.update { it.copy(serviceCostRupees = digits(event.rupees)) }
                is VehiclesEvent.ServiceOdometerChanged ->
                    _uiState.update { it.copy(serviceOdometerKm = digits(event.km)) }
                is VehiclesEvent.ServiceDateChanged -> _uiState.update { it.copy(serviceDate = event.isoDate) }
                else -> Unit
            }
        }

        /** Records the vehicle and clears the form. Input: none. Output: none. */
        private fun addVehicle() {
            val state = _uiState.value
            if (!state.canAdd) return
            viewModelScope.launch {
                report(repository.addVehicle(state.newLabel, state.newClass))
                _uiState.update { it.copy(newLabel = "") }
            }
        }

        /** Records a reading and clears the distance field. Input: [vehicleId]. Output: none. */
        private fun logOdometer(vehicleId: String) {
            val state = _uiState.value
            if (!state.canLogOdometer) return
            viewModelScope.launch {
                report(repository.logOdometer(vehicleId, state.odometerDate, state.odometerKm.toLong()))
                _uiState.update { it.copy(odometerKm = "") }
            }
        }

        /** Records a service and clears its fields. Input: [vehicleId]. Output: none. */
        private fun logService(vehicleId: String) {
            val state = _uiState.value
            if (!state.canLogService) return
            viewModelScope.launch {
                report(
                    repository.logService(
                        vehicleId = vehicleId,
                        isoDate = state.serviceDate,
                        odometerKm = state.serviceOdometerKm.toLong(),
                        cost = rupees(state.serviceCostRupees),
                    ),
                )
                _uiState.update { it.copy(serviceCostRupees = "", serviceOdometerKm = "") }
            }
        }

        /** Records a renewal date and its price. Input: [event]. Output: none. */
        private fun setRenewal(event: VehiclesEvent.SetRenewal) {
            viewModelScope.launch {
                report(
                    repository.setRenewal(
                        vehicleId = event.vehicleId,
                        item = event.item,
                        lastDoneIsoDate = event.isoDate,
                        lastCost = event.costRupees.takeIf { it.isNotBlank() }?.let { rupees(digits(it)) },
                    ),
                )
            }
        }

        /** Runs a one-argument write and reports its refusal. Input: [write]; [id]. Output: none. */
        private fun run(
            write: suspend (String) -> Result<*, com.aicfo.core.common.AppError>,
            id: String,
        ) {
            viewModelScope.launch { report(write(id)) }
        }

        /** Result: the state carries the field a refusal named, or clears it. Input: [result]. */
        private fun report(result: Result<*, com.aicfo.core.common.AppError>) {
            _uiState.update { it.copy(errorCode = (result as? Err)?.error?.code) }
        }

        /** Subscribes to the vehicles; a failure is a banner over the last good list. */
        private fun observeVehicles() {
            repository.observeVehicles()
                .onEach { vehicles -> _uiState.update { it.copy(vehicles = vehicles, isLoaded = true) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure::class.simpleName) } }
                .launchIn(viewModelScope)
        }

        /** Result: today in the profile's own zone, as an ISO date (TIM-001/TIM-002). */
        private fun today(): String = clock.today().toString()
    }

/** Result: the digits of what was typed — a distance or an amount field holds a number or nothing. */
private fun digits(typed: String): String = typed.filter(Char::isDigit)

/** Result: whole rupees as paise (MNY-001). Input: [typed] — digits only. Output: [Money]. */
private fun rupees(typed: String): Money = Money((typed.toLongOrNull() ?: 0L) * PAISE_PER_RUPEE)

/** One rupee is a hundred paise. */
private const val PAISE_PER_RUPEE = 100L
