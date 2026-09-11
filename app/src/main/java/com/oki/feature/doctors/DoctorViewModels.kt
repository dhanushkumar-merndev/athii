package com.oki.feature.doctors

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.oki.AppContainer
import com.oki.core.ai.*
import com.oki.core.storage.*
import com.oki.core.ui.ActionViewModel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

class DoctorsViewModel(private val c: AppContainer) : ActionViewModel() {
    val doctors =
        c.doctors.doctors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun attendance(doctor: Doctor) = action {
        c.doctors.setAttendance(
            doctor.id,
            if (doctor.attendanceStatus == Attendance.PRESENT) Attendance.ABSENT
            else Attendance.PRESENT,
        )
    }

    fun delete(id: String, onDeleted: () -> Unit = {}) = action {
        c.doctors.delete(id)
        onDeleted()
    }
}

data class DoctorAutocomplete(
    val names: List<String>,
    val qualifications: List<String>,
    val departments: List<String>,
    val rooms: List<String>,
    val fromTimes: List<String>,
    val untilTimes: List<String>,
    val hospitals: List<String>,
    val phones: List<String>,
    val notes: List<String>,
)

class DoctorEditorViewModel(
    private val c: AppContainer,
    private val state: SavedStateHandle,
    private val id: String?,
    draft: String?,
) : ActionViewModel() {
    val form =
        state
            .getStateFlow("form", "")
            .map { if (it.isBlank()) null else aiJson.decodeFromString<Doctor>(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val saved = MutableStateFlow(false)
    val autocomplete =
        c.doctors.doctors
            .map { doctors ->
                DoctorAutocomplete(
                    names =
                        doctors
                            .map { it.doctorName }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    qualifications =
                        doctors
                            .map { it.qualification }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    departments =
                        doctors
                            .map { it.department }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    rooms =
                        doctors
                            .map { it.roomOrOpdNumber }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    fromTimes =
                        doctors
                            .map { it.availableFrom }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    untilTimes =
                        doctors
                            .map { it.availableUntil }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    hospitals =
                        doctors
                            .map { it.hospitalOrClinic }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted(),
                    phones =
                        doctors.map { it.phone }.filter { it.isNotBlank() }.distinct().sorted(),
                    notes = doctors.map { it.notes }.filter { it.isNotBlank() }.distinct().sorted(),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                DoctorAutocomplete(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
            )
    val isReview = draft != null

    init {
        if (id == null && draft == null && state.get<String>("form").isNullOrBlank()) {
            change(Doctor(doctorName = ""))
        }
        action {
            if (state.get<String>("form").isNullOrBlank()) {
                val doctor =
                    id?.let { c.doctors.get(it) ?: error("This doctor no longer exists.") }
                        ?: draft?.let {
                            val d = aiJson.decodeFromString<DoctorDraft>(it)
                            Doctor(
                                doctorName = d.doctorName.orEmpty(),
                                qualification = d.qualification.orEmpty(),
                                department = d.department.orEmpty(),
                                roomOrOpdNumber = d.roomOrOpdNumber.orEmpty(),
                                availableFrom = d.availableFrom.orEmpty(),
                                availableUntil = d.availableUntil.orEmpty(),
                                workingDays = d.workingDays,
                                hospitalOrClinic = d.hospitalOrClinic.orEmpty(),
                                phone = d.phone.orEmpty(),
                                notes = d.notes.orEmpty(),
                                source =
                                    Source.valueOf(state.get<String>("draftSource") ?: "IMAGE_SCAN"),
                            )
                        }
                        ?: Doctor(doctorName = "")
                change(doctor)
            }
        }
    }

    fun change(doctor: Doctor) {
        state["form"] = aiJson.encodeToString(doctor)
    }

    fun save() = action {
        val doctor = form.value ?: return@action
        if (id != null && c.doctors.get(id) == null)
            error("This doctor was deleted. Return to the directory.")
        c.doctors.save(doctor)
        saved.value = true
    }
}
