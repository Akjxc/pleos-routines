package com.example.pleos_routines.ui

import android.location.Address
import android.location.Geocoder
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.pleos_routines.R
import com.example.pleos_routines.model.*
import com.example.pleos_routines.vehicle.VehicleSnapshot
import java.util.Locale
import java.util.concurrent.Executors

class RoutineEditorDialog(
    private val activity: AppCompatActivity, private val original: Routine?,
    private val snapshot: VehicleSnapshot, private val currentLocation: GeoPoint?,
    private val save: (Routine, (String?) -> Unit) -> Unit,
    private val delete: ((String?) -> Unit) -> Unit
) {
    fun show(): AlertDialog {
        val draft = original ?: Routine()
        val view = activity.layoutInflater.inflate(R.layout.dialog_routine_editor, null)
        val fields = view.findViewById<LinearLayout>(R.id.editor_fields)
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun LinearLayout.label(text: String) = TextView(activity).also {
            it.text = text
            it.setPadding(0, dp(12), 0, dp(4))
            addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun LinearLayout.section(text: String) = TextView(activity).also {
            it.text = text
            it.textSize = 18f
            it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
            it.setTextColor(android.graphics.Color.rgb(35, 125, 102))
            it.setPadding(0, dp(20), 0, dp(6))
            addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun LinearLayout.input(hint: String, value: String, type: Int = InputType.TYPE_CLASS_NUMBER) = EditText(activity).also {
            it.hint = hint
            it.contentDescription = hint
            it.inputType = type
            it.setText(value)
            it.minHeight = dp(56)
            addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun Spinner.options(values: List<String>) { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values) }
        fun LinearLayout.spinner(caption: String, values: List<String>, selected: Int = 0): Spinner {
            label(caption)
            return Spinner(activity).also {
                it.contentDescription = caption
                it.options(values)
                it.setSelection(selected.coerceAtLeast(0))
                addView(it, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            }
        }
        fun LinearLayout.panel() = LinearLayout(activity).also {
            it.orientation = LinearLayout.VERTICAL
            addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun LinearLayout.check(text: String, checked: Boolean) = CheckBox(activity).also {
            it.text = text
            it.isChecked = checked
            it.minHeight = dp(48)
            addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun selected(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = block()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        fun selectedPosition(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = block(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        fun View.visible(value: Boolean) { visibility = if (value) View.VISIBLE else View.GONE }

        fields.section("루틴 기본 설정")
        val name = fields.input("루틴 이름 (예: 출근 준비)", draft.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            .apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
        fields.section("실행 조건")
        val triggers = listOf(Trigger.MANUAL, Trigger.BATTERY_BELOW, Trigger.AT_TIME, Trigger.LOCATION, Trigger.GEAR)
        val trigger = fields.spinner("실행 조건", listOf("수동 실행", "배터리 기준 이하", "지정 시각 (요일 선택)", "위치 진입·이탈", "기어 변경"), triggers.indexOf(draft.trigger))
        val batteryPanel = fields.panel()
        val threshold = batteryPanel.input("배터리 기준 (%)", draft.thresholdPercent.toString())
        val timePanel = fields.panel()
        timePanel.label("실행 요일")
        val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")
        val dayCheckboxes = mutableListOf<CheckBox>()
        val dayRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val activeDays = draft.daysOfWeek.ifEmpty { listOf(1, 2, 3, 4, 5, 6, 7) }
        dayNames.forEachIndexed { idx, nameStr ->
            val dayNum = idx + 1
            val cb = CheckBox(activity).apply {
                text = nameStr
                isChecked = dayNum in activeDays
            }
            dayCheckboxes.add(cb)
            dayRow.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        timePanel.addView(dayRow, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        timePanel.label("실행 시각")
        val hour = timePanel.input("시 (0~23)", draft.hour.toString())
        val minute = timePanel.input("분 (0~59)", draft.minute.toString())
        val locationPanel = fields.panel()
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        locationPanel.label("검색할 위치")
        val placeQuery = locationPanel.input("장소명 또는 주소 (예: 강남역, 서울시청)", "", InputType.TYPE_CLASS_TEXT)
        val latitude = locationPanel.input("중심 위도 (-90~90)", draft.latitude?.toString().orEmpty(), decimal).apply { visible(false) }
        val longitude = locationPanel.input("중심 경도 (-180~180)", draft.longitude?.toString().orEmpty(), decimal).apply { visible(false) }
        val searchStatus = locationPanel.label(
            if (draft.latitude != null && draft.longitude != null) "선택된 위치: %.5f, %.5f".format(draft.latitude, draft.longitude) else ""
        )
        searchStatus.setTextColor(android.graphics.Color.rgb(185, 201, 216))
        locationPanel.addView(Button(activity).apply {
            text = if (currentLocation == null) "현재 위치를 사용할 수 없음" else "현재 위치를 기준으로 사용"
            isEnabled = currentLocation != null
            setOnClickListener {
                currentLocation?.let {
                    latitude.setText(it.latitude.toString())
                    longitude.setText(it.longitude.toString())
                    searchStatus.text = "선택된 위치: 현재 위치 (%.5f, %.5f)".format(it.latitude, it.longitude)
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        locationPanel.addView(Button(activity).apply {
            text = "장소 검색 및 위치 적용"
            setOnClickListener {
                val query = placeQuery.text.toString().trim()
                if (query.isBlank()) {
                    searchStatus.text = "검색할 장소명이나 주소를 입력하세요."
                    return@setOnClickListener
                }
                isEnabled = false
                searchStatus.text = "장소 검색 중..."
                fun failed() {
                    activity.runOnUiThread {
                        isEnabled = true
                        searchStatus.text = "장소 검색에 실패했습니다. 장소명이나 주소를 다시 확인하세요."
                    }
                }
                fun fallbackHttpSearch(q: String) {
                    Executors.newSingleThreadExecutor().execute {
                        try {
                            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                            val url = java.net.URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&accept-language=ko")
                            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                                setRequestProperty("User-Agent", "PleosRoutinesApp/1.0")
                                connectTimeout = 5000
                                readTimeout = 5000
                            }
                            val response = conn.inputStream.bufferedReader().readText()
                            val jsonArray = org.json.JSONArray(response)
                            if (jsonArray.length() > 0) {
                                val item = jsonArray.getJSONObject(0)
                                val lat = item.getDouble("lat")
                                val lon = item.getDouble("lon")
                                val name = item.optString("display_name", q)
                                activity.runOnUiThread {
                                    isEnabled = true
                                    latitude.setText(lat.toString())
                                    longitude.setText(lon.toString())
                                    searchStatus.text = "선택된 위치: $name"
                                }
                            } else {
                                failed()
                            }
                        } catch (_: Exception) {
                            failed()
                        }
                    }
                }
                fun applyResult(addresses: List<Address>?) {
                    val address = addresses?.firstOrNull {
                        it.hasLatitude() && it.hasLongitude() &&
                            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
                    }
                    if (address == null) {
                        fallbackHttpSearch(query)
                    } else {
                        activity.runOnUiThread {
                            isEnabled = true
                            latitude.setText(address.latitude.toString())
                            longitude.setText(address.longitude.toString())
                            searchStatus.text = "선택된 위치: ${address.getAddressLine(0) ?: query}"
                        }
                    }
                }
                if (!Geocoder.isPresent()) {
                    fallbackHttpSearch(query)
                } else {
                    try {
                        val geocoder = Geocoder(activity, Locale.KOREA)
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) = applyResult(addresses)
                                override fun onError(errorMessage: String?) = fallbackHttpSearch(query)
                            })
                        } else {
                            Executors.newSingleThreadExecutor().execute {
                                try {
                                    @Suppress("DEPRECATION")
                                    applyResult(geocoder.getFromLocationName(query, 5))
                                } catch (_: Exception) {
                                    fallbackHttpSearch(query)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        fallbackHttpSearch(query)
                    }
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        locationPanel.label("감지 반경")
        val radius = locationPanel.input("반경(미터, 100~10000)", draft.radiusMeters.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        locationPanel.label("위치를 중심으로 감지할 거리입니다.")
        val locationDirection = locationPanel.spinner("실행 시점", listOf("설정 위치 안으로 들어올 때", "설정 위치 밖으로 나갈 때"), if (draft.locationExit) 1 else 0)
        locationPanel.label("위치 선택 · 반경 설정 · 실행 시점")
        val gearPanel = fields.panel()
        val gear = gearPanel.spinner("기어 변화", listOf("P단으로 변경될 때", "P단을 벗어날 때"), if (draft.gearParked) 0 else 1)

        fields.section("실행 동작")
        val actions = listOf(Action.NOTICE, Action.SET_TEMPERATURE, Action.AC_ON, Action.VENTILATE, Action.SET_FAN)
        val editableActions = draft.effectiveActions.toMutableList()
        var selectedActionIndex = 0
        fields.label("동작 순서")
        val actionCards = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        fields.addView(actionCards, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fields.label("동작 카드를 누르면 상세 설정을 편집할 수 있습니다.")
        val initialAction = editableActions.firstOrNull() ?: ActionItem()
        val action = fields.spinner("실행 동작", listOf("안내 표시", "공조 온도 설정", "에어컨 켜기", "송풍·환기", "팬 속도 설정"), actions.indexOf(initialAction.action))
        val actionButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(activity).apply { text = "동작 적용" }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(Button(activity).apply { text = "동작 추가" }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(Button(activity).apply { text = "선택 동작 삭제" }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        val applyActionButton = actionButtons.getChildAt(0) as Button
        val addActionButton = actionButtons.getChildAt(1) as Button
        val removeActionButton = actionButtons.getChildAt(2) as Button
        fields.addView(actionButtons, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val messagePanel = fields.panel()
        val message = messagePanel.input("표시할 안내문", initialAction.message, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            .apply { filters = arrayOf(InputFilter.LengthFilter(200)); minHeight = dp(72) }
        val climatePanel = fields.panel()
        val climateSafety = climatePanel.label("수동 실행·편집은 P단에서 가능합니다.")
        val area = climatePanel.spinner("공조 영역", listOf("지원 공조 영역 없음"))
        val temperaturePanel = climatePanel.panel()
        val temperature = temperaturePanel.spinner("설정 온도", listOf("설정 가능한 온도 없음"))
        val fanPanel = climatePanel.panel()
        val fan = fanPanel.spinner("팬 속도", listOf("설정 가능한 팬 속도 없음"))
        val ventilationNote = climatePanel.label("에어컨을 끄고 현재 송풍을 유지합니다. 팬이 켜져 있어야 실행합니다.")
        val capability = fields.label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }

        fields.section("추가 조건")
        fields.label("기본 실행 조건이 충족된 뒤 차량 상태를 한 번 더 확인하는 안전 필터입니다. 모든 조건을 만족해야 실행합니다.")
        val conditionItems = draft.effectiveConditions.toMutableList()
        var selectedConditionIndex = 0
        val conditionCards = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fields.addView(conditionCards, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val conditionTypes = ConditionKind.values().toList()
        val conditionEditor = fields.panel()
        val conditionType = conditionEditor.spinner("조건 종류", conditionTypes.map {
            when (it) {
                ConditionKind.AC_STATE -> "에어컨 상태"
                ConditionKind.POWER_STATE -> "공조 전원 상태"
                ConditionKind.BATTERY_RANGE -> "배터리 범위"
                ConditionKind.PARKED_STATE -> "주차 상태"
                ConditionKind.DEFROST_OFF -> "성에 제거 꺼짐"
            }
        })
        val conditionValue = conditionEditor.spinner("상태", listOf("켜짐", "꺼짐"))
        val batteryRangePanel = conditionEditor.panel()
        val batteryMin = batteryRangePanel.input("최소 배터리 (0~100%, 비우면 제한 없음)", "")
        val batteryMax = batteryRangePanel.input("최대 배터리 (0~100%, 비우면 제한 없음)", "")
        val parkedCondition = conditionEditor.spinner("주차 상태", listOf("P단", "P단 아님"))
        val defrost = conditionEditor.check("성에 제거가 꺼져 있을 때만 실행", true).apply { isEnabled = false }
        val conditionButtons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val applyConditionButton = Button(activity).apply { text = "조건 적용" }
        val addConditionButton = Button(activity).apply { text = "조건 추가" }
        val removeConditionButton = Button(activity).apply { text = "선택 조건 삭제" }
        conditionButtons.addView(applyConditionButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        conditionButtons.addView(addConditionButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        conditionButtons.addView(removeConditionButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        fields.addView(conditionButtons, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fields.label("조건 카드를 누르면 상세 설정을 편집할 수 있습니다. 차량 값을 확인할 수 없으면 실행하지 않습니다.")
        fun hasClimateCondition(): Boolean = conditionItems.any { it.kind == ConditionKind.AC_STATE || it.kind == ConditionKind.POWER_STATE }
        fun saveSelectedCondition() {
            if (selectedConditionIndex !in conditionItems.indices) return
            val old = conditionItems[selectedConditionIndex]
            conditionItems[selectedConditionIndex] = when (conditionTypes[conditionType.selectedItemPosition]) {
                ConditionKind.AC_STATE -> old.copy(kind = ConditionKind.AC_STATE, acOn = conditionValue.selectedItemPosition == 0)
                ConditionKind.POWER_STATE -> old.copy(kind = ConditionKind.POWER_STATE, powerOn = conditionValue.selectedItemPosition == 0)
                ConditionKind.BATTERY_RANGE -> old.copy(kind = ConditionKind.BATTERY_RANGE, batteryMin = batteryMin.text.toString().trim().toIntOrNull(), batteryMax = batteryMax.text.toString().trim().toIntOrNull())
                ConditionKind.PARKED_STATE -> old.copy(kind = ConditionKind.PARKED_STATE, parked = parkedCondition.selectedItemPosition == 0)
                ConditionKind.DEFROST_OFF -> old.copy(kind = ConditionKind.DEFROST_OFF, requireDefrostOff = true)
            }
        }
        fun refreshConditionEditor(kind: ConditionKind) {
            conditionValue.visible(kind == ConditionKind.AC_STATE || kind == ConditionKind.POWER_STATE)
            batteryRangePanel.visible(kind == ConditionKind.BATTERY_RANGE)
            parkedCondition.visible(kind == ConditionKind.PARKED_STATE)
            defrost.visible(kind == ConditionKind.DEFROST_OFF)
        }
        lateinit var refreshConditionCards: () -> Unit
        fun loadSelectedCondition() {
            val item = conditionItems.getOrNull(selectedConditionIndex)
            conditionEditor.visible(item != null)
            if (item == null) return
            conditionType.setSelection(conditionTypes.indexOf(item.kind).coerceAtLeast(0))
            conditionValue.setSelection(if (item.acOn ?: item.powerOn ?: true) 0 else 1)
            batteryMin.setText(item.batteryMin?.toString().orEmpty()); batteryMax.setText(item.batteryMax?.toString().orEmpty())
            parkedCondition.setSelection(if (item.parked == false) 1 else 0)
            defrost.isChecked = true
            refreshConditionEditor(item.kind)
        }
        refreshConditionCards = {
            conditionCards.removeAllViews()
            if (conditionItems.isEmpty()) {
                conditionCards.addView(TextView(activity).apply {
                    text = "추가된 조건이 없습니다. 아래의 ‘조건 추가’를 눌러 안전 필터를 추가하세요."
                    setTextColor(Color.rgb(90, 105, 110))
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.rgb(245, 247, 248))
                        setStroke(dp(1), Color.rgb(220, 226, 230))
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            conditionItems.forEachIndexed { index, item ->
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); isClickable = true; isFocusable = true
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (index == selectedConditionIndex) Color.rgb(224, 245, 238) else Color.rgb(245, 247, 248)); setStroke(dp(1), if (index == selectedConditionIndex) Color.rgb(35, 125, 102) else Color.rgb(220, 226, 230)) }
                    setOnClickListener { if (index != selectedConditionIndex) { selectedConditionIndex = index; refreshConditionCards(); loadSelectedCondition() } }
                }
                card.addView(TextView(activity).apply { text = "${index + 1}. ${item.title()}"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                card.addView(TextView(activity).apply { text = when (item.kind) {
                    ConditionKind.AC_STATE -> if (item.acOn == true) "켜져 있을 때" else "꺼져 있을 때"
                    ConditionKind.POWER_STATE -> if (item.powerOn == true) "켜져 있을 때" else "꺼져 있을 때"
                    ConditionKind.BATTERY_RANGE -> "${item.batteryMin ?: 0}~${item.batteryMax ?: 100}%"
                    ConditionKind.PARKED_STATE -> if (item.parked == true) "P단일 때" else "P단이 아닐 때"
                    ConditionKind.DEFROST_OFF -> "성에 제거가 꺼져 있을 때"
                }; setTextColor(Color.rgb(90, 105, 110)) })
                conditionCards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            removeConditionButton.isEnabled = conditionItems.isNotEmpty()
        }
        conditionType.onItemSelectedListener = selected {
            refreshConditionEditor(conditionTypes[conditionType.selectedItemPosition])
        }
        applyConditionButton.setOnClickListener {
            saveSelectedCondition()
            refreshConditionCards()
        }
        addConditionButton.setOnClickListener { conditionItems.add(RoutineCondition(ConditionKind.AC_STATE, acOn = true)); selectedConditionIndex = conditionItems.lastIndex; refreshConditionCards(); loadSelectedCondition() }
        removeConditionButton.setOnClickListener { if (conditionItems.isNotEmpty()) { conditionItems.removeAt(selectedConditionIndex); selectedConditionIndex = selectedConditionIndex.coerceAtMost(conditionItems.lastIndex); refreshConditionCards(); loadSelectedCondition() } }
        refreshConditionCards(); loadSelectedCondition()
        val error = fields.label("").apply {
            setTextColor(android.graphics.Color.rgb(255, 184, 168))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        var areaIds = emptyList<String>()
        var temperatureChoices = emptyList<Float>()
        var fanChoices = emptyList<Int>()
        var activeAction: Action? = null
        var activeArea: String? = null
        val rememberedAreas = mutableMapOf<Action, String>()
        val rememberedTemperatures = mutableMapOf<String, Float>()
        val rememberedFans = mutableMapOf<String, Int>()
        var unavailable: String? = null
        lateinit var loadSelectedAction: () -> Unit
        fun currentActionItem(): ActionItem = editableActions.getOrNull(selectedActionIndex) ?: ActionItem()
        fun rememberValues() {
            activeArea?.let { id ->
                temperatureChoices.getOrNull(temperature.selectedItemPosition)?.let { rememberedTemperatures[id] = it }
                fanChoices.getOrNull(fan.selectedItemPosition)?.let { rememberedFans[id] = it }
                activeAction?.let { rememberedAreas[it] = id }
            }
        }
        fun areaLabel(id: String): String = snapshot.areas.firstOrNull { it.id == id }?.label ?: "공조 영역 ${areaIds.indexOf(id) + 1}"
        fun updateAreaValues() {
            val id = areaIds.getOrNull(area.selectedItemPosition)
            if (id == activeArea) return
            rememberValues()
            activeArea = id
            temperatureChoices = snapshot.areas.firstOrNull { it.id == id }?.choices().orEmpty()
            temperature.options(temperatureChoices.map { "${it}°C" }.ifEmpty { listOf("설정 가능한 온도 없음") })
            val desiredTemperature = rememberedTemperatures[id] ?: currentActionItem().temperatureC
            temperature.setSelection(temperatureChoices.indexOfFirst { kotlin.math.abs(it - desiredTemperature) < .05f }.coerceAtLeast(0))
            fanChoices = snapshot.fanRanges[id]?.takeIf { !it.isEmpty() && it.last.toLong() - it.first.toLong() < 300 }?.toList().orEmpty()
            fan.options(fanChoices.map { "${it}단계" }.ifEmpty { listOf("설정 가능한 팬 속도 없음") })
            fan.setSelection(fanChoices.indexOf(rememberedFans[id] ?: currentActionItem().fanSpeed).coerceAtLeast(0))
        }
        fun updateAction() {
            val chosen = actions[action.selectedItemPosition]
            val stateCondition = hasClimateCondition()
            val supportedAreas = when (chosen) {
                Action.NOTICE -> if (stateCondition) (snapshot.areas.map { it.id } + snapshot.acAreas + snapshot.fanRanges.keys + snapshot.acStates.keys + snapshot.powerStates.keys).distinct().sorted() else emptyList()
                Action.SET_TEMPERATURE -> snapshot.areas.filter { it.choices().isNotEmpty() }.map { it.id }
                Action.AC_ON -> snapshot.acAreas.toList().sorted()
                Action.VENTILATE -> snapshot.acAreas.filter { acId -> snapshot.fanSpeeds.keys.any { fanId -> acId.split('+').toSet().containsAll(fanId.split('+')) } }.sorted()
                Action.SET_FAN -> snapshot.fanRanges.filterValues { !it.isEmpty() && it.last.toLong() - it.first.toLong() < 300 }.keys.sorted()
            }
            if (chosen != activeAction || supportedAreas != areaIds) {
                rememberValues()
                activeAction = chosen
                activeArea = null
                temperatureChoices = emptyList()
                fanChoices = emptyList()
                areaIds = supportedAreas
                area.options(areaIds.map(::areaLabel).ifEmpty { listOf("지원 공조 영역 없음") })
                area.setSelection(areaIds.indexOf(rememberedAreas[chosen] ?: currentActionItem().targetAreaId).coerceAtLeast(0))
                updateAreaValues()
            }
            messagePanel.visible(chosen == Action.NOTICE)
            climatePanel.visible(chosen != Action.NOTICE || stateCondition)
            climateSafety.visible(chosen != Action.NOTICE)
            temperaturePanel.visible(chosen == Action.SET_TEMPERATURE)
            fanPanel.visible(chosen == Action.SET_FAN)
            ventilationNote.visible(chosen == Action.VENTILATE)
            unavailable = if ((chosen != Action.NOTICE || stateCondition) && areaIds.isEmpty()) when (chosen) {
                Action.NOTICE -> "공조 상태 조건을 확인할 영역이 없어 저장할 수 없습니다. 차량 연결을 확인하세요."
                Action.SET_TEMPERATURE -> "온도 설정을 지원하는 공조 영역이 없어 저장할 수 없습니다. 차량 연결을 확인하세요."
                Action.AC_ON -> "에어컨 켜기를 지원하는 공조 영역이 없어 저장할 수 없습니다. 차량 연결을 확인하세요."
                Action.VENTILATE -> "A/C 제어와 작동 중인 팬 상태를 확인할 영역이 없어 송풍 유지를 저장할 수 없습니다."
                Action.SET_FAN -> "팬 속도 설정을 지원하는 공조 영역이 없어 저장할 수 없습니다. 차량 연결을 확인하세요."
                else -> null
            } else null
            capability.text = unavailable.orEmpty()
            capability.visible(unavailable != null)
        }
        fun saveSelectedAction() {
            if (selectedActionIndex !in editableActions.indices) return
            val selected = currentActionItem()
            editableActions[selectedActionIndex] = selected.copy(
                action = actions[action.selectedItemPosition],
                targetAreaId = areaIds.getOrNull(area.selectedItemPosition) ?: selected.targetAreaId,
                temperatureC = temperatureChoices.getOrNull(temperature.selectedItemPosition) ?: selected.temperatureC,
                message = message.text.toString().trim(),
                fanSpeed = fanChoices.getOrNull(fan.selectedItemPosition) ?: selected.fanSpeed
            )
        }
        fun refreshActionCards() {
            actionCards.removeAllViews()
            editableActions.forEachIndexed { index, item ->
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    isClickable = true
                    isFocusable = true
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (index == selectedActionIndex) Color.rgb(224, 245, 238) else Color.rgb(245, 247, 248))
                        setStroke(dp(1), if (index == selectedActionIndex) Color.rgb(35, 125, 102) else Color.rgb(220, 226, 230))
                    }
                    setOnClickListener {
                        if (index == selectedActionIndex) return@setOnClickListener
                        selectedActionIndex = index
                        refreshActionCards()
                        loadSelectedAction()
                    }
                }
                val title = TextView(activity).apply {
                    text = "${index + 1}. ${item.summary()}"
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.rgb(30, 45, 50))
                }
                val detail = TextView(activity).apply {
                    val target = snapshot.areas.firstOrNull { it.id == item.targetAreaId }?.label
                    text = buildString {
                        if (!target.isNullOrBlank()) append(target)
                        if (item.action == Action.NOTICE && item.message.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(item.message)
                        }
                    }
                    setTextColor(Color.rgb(90, 105, 110))
                    visible(text.isNotBlank())
                }
                card.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                card.addView(detail, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                actionCards.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                })
            }
            removeActionButton.isEnabled = editableActions.size > 1
        }
        loadSelectedAction = {
            val selected = currentActionItem()
            action.setSelection(actions.indexOf(selected.action).coerceAtLeast(0))
            message.setText(selected.message)
            updateAction()
        }
        fun updateTrigger() {
            val chosen = triggers[trigger.selectedItemPosition]
            batteryPanel.visible(chosen == Trigger.BATTERY_BELOW)
            timePanel.visible(chosen == Trigger.AT_TIME)
            locationPanel.visible(chosen == Trigger.LOCATION)
            gearPanel.visible(chosen == Trigger.GEAR)
        }
        area.onItemSelectedListener = selected { updateAreaValues() }
        action.onItemSelectedListener = selected {
            updateAction()
        }
        applyActionButton.setOnClickListener {
            saveSelectedAction()
            refreshActionCards()
        }
        addActionButton.setOnClickListener {
            editableActions.add(ActionItem())
            selectedActionIndex = editableActions.lastIndex
            refreshActionCards()
            loadSelectedAction()
        }
        removeActionButton.setOnClickListener {
            if (editableActions.size <= 1) return@setOnClickListener
            editableActions.removeAt(selectedActionIndex)
            selectedActionIndex = selectedActionIndex.coerceAtMost(editableActions.lastIndex)
            refreshActionCards()
            loadSelectedAction()
        }
        trigger.onItemSelectedListener = selected { updateTrigger() }
        updateTrigger()
        updateAction()
        refreshActionCards()

        val dialog = AlertDialog.Builder(activity).setTitle(if (original == null) "루틴 추가" else "루틴 편집")
            .setView(view).setPositiveButton("저장", null).setNegativeButton("취소", null)
            .apply { if (original != null) setNeutralButton("삭제", null) }.create()
        dialog.setOnShowListener {
            var submitting = false
            fun lock(value: Boolean) {
                submitting = value
                dialog.setCancelable(!value)
                dialog.setCanceledOnTouchOutside(!value)
                listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { dialog.getButton(it)?.isEnabled = !value }
            }
            fun complete(problem: String?) {
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    lock(false)
                    if (problem == null) dialog.dismiss() else error.text = problem
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (submitting) return@setOnClickListener
                saveSelectedAction()
                val firstAction = editableActions.firstOrNull() ?: ActionItem()
                val chosenTrigger = triggers[trigger.selectedItemPosition]
                val chosenAction = firstAction.action
                val enteredHour = hour.text.toString().toIntOrNull()
                val enteredMinute = minute.text.toString().toIntOrNull()
                val enteredLatitude = latitude.text.toString().toDoubleOrNull()
                val enteredLongitude = longitude.text.toString().toDoubleOrNull()
                val enteredRadius = radius.text.toString().toFloatOrNull()
                saveSelectedCondition()
                val selectedDays = dayCheckboxes.mapIndexedNotNull { index, cb -> if (cb.isChecked) index + 1 else null }
                val daysOfWeek = if (selectedDays.size == 7) emptyList() else selectedDays
                val inputProblem = when {
                    unavailable != null -> unavailable
                    chosenTrigger == Trigger.AT_TIME && selectedDays.isEmpty() -> "최소 하나 이상의 실행 요일을 선택하세요."
                    chosenTrigger == Trigger.AT_TIME && (enteredHour == null || enteredHour !in 0..23 || enteredMinute == null || enteredMinute !in 0..59) -> "시각을 0~23시, 0~59분으로 입력하세요."
                    chosenTrigger == Trigger.LOCATION && (enteredLatitude == null || !enteredLatitude.isFinite() || enteredLatitude !in -90.0..90.0 || enteredLongitude == null || !enteredLongitude.isFinite() || enteredLongitude !in -180.0..180.0) -> "장소 검색 또는 현재 위치 사용 버튼으로 위치를 선택하세요."
                    chosenTrigger == Trigger.LOCATION && (enteredRadius == null || !enteredRadius.isFinite() || enteredRadius !in 100f..10000f) -> "위치 반경을 100~10000미터로 입력하세요."
                    else -> null
                }
                if (inputProblem != null) { error.text = inputProblem; return@setOnClickListener }
                val result = draft.copy(
                    name = name.text.toString().trim(), trigger = chosenTrigger, action = chosenAction,
                    thresholdPercent = if (chosenTrigger == Trigger.BATTERY_BELOW) threshold.text.toString().toIntOrNull() ?: 0 else draft.thresholdPercent.coerceIn(1, 95),
                    message = firstAction.message, targetAreaId = firstAction.targetAreaId,
                    temperatureC = firstAction.temperatureC,
                    hour = enteredHour?.takeIf { it in 0..23 } ?: draft.hour, minute = enteredMinute?.takeIf { it in 0..59 } ?: draft.minute,
                    latitude = enteredLatitude ?: draft.latitude, longitude = enteredLongitude ?: draft.longitude,
                    radiusMeters = enteredRadius?.takeIf { it.isFinite() && it > 0f } ?: draft.radiusMeters,
                    locationExit = locationDirection.selectedItemPosition == 1, gearParked = gear.selectedItemPosition == 0,
                    fanSpeed = firstAction.fanSpeed,
                    daysOfWeek = daysOfWeek,
                    actions = if (editableActions.size > 1) editableActions.toList() else emptyList(),
                    conditions = RoutineConditions(items = conditionItems.toList())
                )
                val problem = result.validationError(snapshot.areas)
                if (problem != null) error.text = problem else {
                    lock(true)
                    try { save(result, ::complete) } catch (failure: Exception) { complete("저장 요청에 실패했습니다. 다시 시도하세요.") }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                if (!submitting) {
                    lock(true)
                    try { delete(::complete) } catch (failure: Exception) { complete("삭제 요청에 실패했습니다. 다시 시도하세요.") }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(640).coerceAtMost(activity.resources.displayMetrics.widthPixels - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
