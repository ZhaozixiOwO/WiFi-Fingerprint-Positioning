# VesselLoc 项目功能介绍 / Project Introduction

## 一、功能概述 / 1. Feature Overview

VesselLoc 是一款基于 Wi-Fi 指纹的室内定位 Android 应用，面向校园、办公楼宇等室内场景，利用现有 Wi-Fi 接入点（Access Point，AP）信号实现移动端的实时室内位置感知。

*VesselLoc is an Android indoor positioning application based on Wi-Fi fingerprinting. Designed for indoor environments such as campuses and office buildings, it leverages existing Wi-Fi Access Point (AP) signals to provide real-time indoor location awareness on mobile devices.*

[//]: # (**（图 1 / Figure 1：应用主界面——定位状态展示 / App main screen — location status）**)
<!-- 请在此处插入主界面截图 / Insert main screen screenshot here -->

**核心功能模块如下：** / **Core feature modules:**

**1. 房间级定位** / **Room-Level Positioning**

通过解析 Wi-Fi AP 的命名编码（格式为 `楼栋/楼层/房间号AP编号`，如 `T3/1F/103AP03` 对应 T3 栋 1 层 103 房间），结合多 AP 信号强度的加权投票机制，实时推断用户当前所在的楼栋、楼层及房间号。

*By parsing Wi-Fi AP naming conventions (format: `Building/Floor/RoomID-APNumber`, e.g., `T3/1F/103AP03` → Building T3, 1st Floor, Room 103), combined with a weighted voting mechanism across multiple AP signal strengths, the app infers the user's current building, floor, and room in real time.*

**2. 坐标级定位** / **Coordinate-Level Positioning**

在房间定位的基础上，利用预先采集的 Wi-Fi 指纹数据库，通过 K 近邻（KNN）算法预测用户在楼层平面图上的精确 (x, y) 坐标，并在 SVG 矢量地图上以标记点形式可视化呈现。

*Building on room-level positioning, the app uses a pre-collected Wi-Fi fingerprint database and a K-Nearest Neighbors (KNN) algorithm to predict the user's precise (x, y) coordinates on the floor plan, visualized as a marker on the SVG map.*

[//]: # (**（图 2 / Figure 2：坐标定位结果在楼层平面图上的可视化 / Coordinate positioning visualized on the floor plan）**)
<!-- 请在此处插入定位圆点截图 / Insert positioning marker screenshot here -->

**3. 指纹采集与标定** / **Fingerprint Collection & Calibration**

提供专用的标定模式。用户可在楼层平面图上点击实际所在位置，系统自动记录该位置的 Wi-Fi 信号特征向量（全部可探测 AP 的 MAC 地址 → RSSI 信号强度映射），构造一条指纹记录。所有指纹数据以 JSON 格式持久化存储于本地，构成后续定位匹配所需的参考数据库。

*A dedicated calibration mode is provided. Users tap their actual location on the floor plan, and the system records the Wi-Fi signal feature vector at that position (all detectable AP MAC addresses → RSSI signal strength mappings), creating a fingerprint record. All fingerprint data is persisted locally in JSON format, forming the reference database for subsequent positioning.*

[//]: # (**（图 3 / Figure 3：标定界面——在地图上点击以采集指纹 / Calibration screen — tap the map to collect fingerprints）**)
<!-- 请在此处插入标定界面截图 / Insert calibration screen screenshot here -->

**4. Wi-Fi 状态监控面板** / **Wi-Fi Status Monitoring Dashboard**

实时展示当前 Wi-Fi 连接的详细参数，包括 SSID、BSSID、IP 地址、网关、子网掩码、DNS 服务器、RSSI、频段、信道、链路速率以及 Wi-Fi 协议版本。同时提供扫描摘要信息（全部探测 AP 数量、已知 AP 数量及各 AP 信号强度），所有参数附有中英文双语说明，便于调试与问题排查。

*Displays detailed real-time Wi-Fi connection parameters including SSID, BSSID, IP address, gateway, subnet mask, DNS servers, RSSI, frequency band, channel, link speed, and Wi-Fi protocol version. Also provides a scan summary (total detected APs, known APs, and per-AP signal strength), with all parameters annotated in both Chinese and English for debugging and troubleshooting.*

[//]: # (**（图 4 / Figure 4：参数说明界面 / Parameter reference screen）**)
<!-- 请在此处插入参数说明界面截图 / Insert parameter reference screenshot here -->

**5. 楼层平面图交互** / **Interactive Floor Plan**

支持双指缩放与拖拽平移操作，用户可自由浏览楼层平面图。定位结果以动态生成的标记点实时叠加于地图之上。

*Supports pinch-to-zoom and drag-to-pan, allowing users to freely navigate the floor plan. Positioning results are overlaid in real time as dynamically generated markers on the map.*

---

## 二、技术方案 / 2. Technical Approach

### 2.1 整体架构 / Overall Architecture

应用采用单 Activity 多页面的 Android 原生架构，使用 Kotlin 语言开发。最低兼容 Android 7.0（API Level 24），目标 SDK 版本为 36。项目包含三个主要页面：

*The app uses a single-Activity, multi-page native Android architecture developed in Kotlin. Minimum supported version is Android 7.0 (API Level 24), targeting SDK version 36. The project includes three main screens:*

| 页面 / Screen | 功能 / Function |
|------|------|
| HomeActivity | 主定位页面，实时展示房间推断结果、坐标预测结果、Wi-Fi 调试信息及楼层平面图 / *Main positioning screen: real-time room inference, coordinate prediction, Wi-Fi diagnostics, and floor plan* |
| CalibrationActivity | 指纹标定页面，支持在地图上点击采集指纹、管理已采集的指纹列表 / *Fingerprint calibration screen: tap-to-collect on the map, manage collected fingerprints* |
| InfoActivity | 参数说明页面，提供所有 Wi-Fi 指标的中英文双语释义 / *Parameter reference screen: bilingual explanations of all Wi-Fi metrics* |

[//]: # (**（图 5 / Figure 5：应用页面结构与导航关系 / App screen structure and navigation）**)
<!-- 请在此处插入应用页面导航示意图 / Insert navigation diagram here -->

### 2.2 定位流程 / Positioning Pipeline

定位 pipeline 由两层递进式推断构成：

*The positioning pipeline consists of two progressive inference layers:*

**第一层：房间级推断（AP 命名解析 + 加权投票）** / **Layer 1: Room-Level Inference (AP Name Parsing + Weighted Voting)**

1. 对每次 Wi-Fi 扫描返回的全部 AP 进行过滤，仅保留 MAC 地址在白名单中的已知 AP；
2. 按信号强度降序排列，取前 8 个最强 AP；
3. 解析每个 AP 名称中编码的楼栋、楼层、房间信息；
4. 以 RSSI 为权重（`weight = clamp(RSSI + 100, 5, 80)`）进行累加投票；
5. 得分最高的（楼栋, 楼层, 房间）组合作为当前房间推断结果；
6. 引入滞后防抖机制：房间切换时，新候选房间的得分须超过上一结果得分的 110%，否则维持原结果，避免相邻房间间的频繁跳变。

*1. Filter all APs from each Wi-Fi scan, keeping only known APs whose MAC addresses are in the whitelist;*
*2. Sort by signal strength descending, taking the top 8 strongest APs;*
*3. Parse the building, floor, and room information encoded in each AP name;*
*4. Accumulate votes using RSSI as weight (`weight = clamp(RSSI + 100, 5, 80)`);*
*5. The (building, floor, room) combination with the highest score is the current room inference result;*
*6. A hysteresis/debounce mechanism is applied: when switching rooms, the new candidate must exceed 110% of the previous result's score, otherwise the previous result is retained to prevent flickering between adjacent rooms.*

**第二层：坐标级推断（Wi-Fi 指纹 KNN 匹配）** / **Layer 2: Coordinate-Level Inference (Wi-Fi Fingerprint KNN Matching)**

1. 将当前扫描的 RSSI 向量与指纹库中所有指纹进行比对；
2. 对每条指纹，计算与当前 RSSI 向量在交集 BSSID 上的欧氏距离平方和；
3. 选取距离最小的 K=3 条指纹；
4. 以距离倒数作为权重，对三条指纹的坐标进行加权平均，得到原始预测坐标；
5. 通过 5 帧滑动窗口对坐标序列进行平滑处理，抑制单帧噪声。

*1. Compare the current scan's RSSI vector against all fingerprints in the database;*
*2. For each fingerprint, compute the sum of squared Euclidean distances over the intersection of BSSIDs with the current RSSI vector;*
*3. Select the K=3 fingerprints with the smallest distances;*
*4. Compute a distance-weighted (inverse distance) average of the three fingerprint coordinates to obtain the raw predicted position;*
*5. Smooth the coordinate sequence through a 5-frame sliding window to suppress single-frame noise.*

[//]: # (**（图 6 / Figure 6：定位算法流程示意 / Positioning algorithm flow）**)
<!-- 请在此处插入定位算法流程图 / Insert algorithm flowchart here -->

### 2.3 数据采集与刷新调度 / Data Collection & Refresh Scheduling

为平衡定位实时性与 Android 系统的 Wi-Fi 扫描限频策略，应用采用以下调度机制：

*To balance positioning real-timeness with Android's Wi-Fi scan throttling policy, the app uses the following scheduling mechanisms:*

- **扫描触发间隔** / **Scan trigger interval**：每 5 秒调用一次 `WifiManager.startScan()` 发起主动扫描 / *Invoke `WifiManager.startScan()` every 5 seconds to initiate an active scan*；
- **结果轮询间隔** / **Result polling interval**：每 1 秒检查一次 `wifiManager.scanResults` 缓存，通过比较 `timestamp` 字段判断是否有新数据到达 / *Check `wifiManager.scanResults` cache every 1 second, comparing the `timestamp` field to determine if new data has arrived*；
- **结果过期判定** / **Result staleness threshold**：若最新扫描结果的存活时间超过 12 秒，则标记为 stale 数据，不参与定位运算 / *If the latest scan result age exceeds 12 seconds, it is marked as stale and excluded from positioning calculations*；
- **手动刷新冷却** / **Manual refresh cooldown**：手动刷新按钮设有 12 秒冷却时间，防止频繁触发被系统拒绝 / *The manual refresh button has a 12-second cooldown to prevent the system from rejecting frequent trigger attempts*；
- **广播监听** / **Broadcast monitoring**：同时注册 `SCAN_RESULTS_AVAILABLE_ACTION` 广播接收器，作为扫描完成的补充通知通道 / *Also registers a `SCAN_RESULTS_AVAILABLE_ACTION` broadcast receiver as a supplementary notification channel for scan completion.*

### 2.4 指纹数据管理 / Fingerprint Data Management

- 每条指纹记录包含三个字段：坐标 (x, y) 及该位置的 RSSI 信号向量（`Map<BSSID, RSSI>`）/ *Each fingerprint record contains three fields: coordinates (x, y) and the RSSI signal vector at that location (`Map<BSSID, RSSI>`)*；
- 所有指纹数据序列化为 JSON 格式，存储于应用私有目录下的 `fingerprints.json` 文件 / *All fingerprint data is serialized as JSON and stored in the `fingerprints.json` file under the app's private directory*；
- 标定页面支持指纹的逐条删除、批量清空，删除操作自动同步更新 SVG 地图上的标记点索引 / *The calibration screen supports individual fingerprint deletion and bulk clearing; deletion automatically syncs marker indices on the SVG map.*

### 2.5 AP 信息映射 / AP Information Mapping

- `ApNameMapping`：维护 MAC 地址到人类可读名称的映射表，当前覆盖 T3、T6、T7、T8 四栋楼约 20 个 AP。命名遵循统一规范：`楼栋/楼层/房间号AP编号` / *Maintains a MAC-to-human-readable-name mapping table, currently covering approximately 20 APs across four buildings (T3, T6, T7, T8). Naming follows the convention: `Building/Floor/RoomID-APNumber`*；
- `ApRoomMapping`：基于同一套已知 AP 的 MAC 地址白名单，用于扫描结果过滤，排除无关 AP 对定位计算的干扰 / *A MAC address whitelist based on the same set of known APs, used to filter scan results and exclude irrelevant APs from positioning calculations.*

### 2.6 前端渲染方案 / Frontend Rendering

- 楼层平面图采用 WebView 加载内联 HTML 的方式渲染原始 SVG 文件（855×815 画布）/ *The floor plan is rendered by loading the raw SVG file (855×815 canvas) via a WebView with inline HTML*；
- 加载时自动处理 SVG 的 `preserveAspectRatio` 属性修正、白底去除、黑底适配等兼容性问题 / *On load, automatically handles SVG compatibility issues such as `preserveAspectRatio` correction, white background removal, and dark background adaptation*；
- 定位标记点通过 `WebView.evaluateJavascript()` 动态向 SVG DOM 注入或移除 `<circle>` 元素实现 / *Positioning markers are implemented by dynamically injecting or removing `<circle>` elements into the SVG DOM via `WebView.evaluateJavascript()`*；
- WebView 与外层 ScrollView 之间的触摸事件冲突通过 `requestDisallowInterceptTouchEvent` 协调，确保双指缩放操作流畅 / *Touch event conflicts between the WebView and the outer ScrollView are resolved via `requestDisallowInterceptTouchEvent` to ensure smooth pinch-to-zoom.*

[//]: # (**（图 7 / Figure 7：技术架构总览 / Technical architecture overview）**)
<!-- 请在此处插入技术架构图 / Insert architecture diagram here -->

---

## 三、效果评估 / 3. Evaluation

### 3.1 技术优势 / Technical Strengths

- **双层定位互补** / **Complementary dual-layer positioning**：房间 AP 命名法在 AP 密集部署区域可快速提供稳定的房间级结果；指纹 KNN 匹配在已采集指纹的区域可进一步提供精确坐标。两者并行运行，互不依赖 / *Room-level AP naming provides fast, stable room results in areas with dense AP deployment; fingerprint KNN matching provides precise coordinates in areas with collected fingerprints. Both run in parallel without mutual dependency*；
- **防抖机制完善** / **Robust debounce mechanisms**：房间推断具有 10% 滞后阈值，坐标输出具有 5 帧滑动窗口平滑，有效抑制信号波动导致的结果跳变 / *Room inference uses a 10% hysteresis threshold, and coordinate output uses 5-frame sliding window smoothing, effectively suppressing result flickering caused by signal fluctuations*；
- **系统适配充分** / **Comprehensive system compatibility**：针对 Android 不同版本（API 24–36）的权限模型差异、扫描限频策略、位置服务依赖等均有对应的兼容处理 / *Handles differences across Android versions (API 24–36) in permission models, scan throttling policies, and location service dependencies*；
- **调试信息丰富** / **Rich debugging information**：实时暴露 `startScan()` 返回值、连续失败计数、结果新鲜度等底层状态，便于定位异常根因 / *Exposes low-level status such as `startScan()` return values, consecutive failure counts, and result freshness in real time for root cause analysis.*

### 3.2 当前局限与改进方向 / Current Limitations & Future Improvements

- **AP 映射为硬编码静态配置** / **Hard-coded AP mappings**：`ApNameMapping` 与 `ApRoomMapping` 中的 MAC 映射关系均以代码常量形式写入，新增或变更 AP 需修改源码并重新编译发布。后续可考虑迁移至配置文件或服务端下发 / *MAC mappings in `ApNameMapping` and `ApRoomMapping` are hard-coded as source constants; adding or changing APs requires modifying source code and re-releasing. A future improvement could be migrating to configuration files or server-side delivery*；
- **指纹库依赖人工逐点采集** / **Fingerprint database relies on manual point-by-point collection**：当前定位精度完全取决于标定阶段人工采集的指纹密度与空间覆盖范围，缺乏自动众包或半监督更新机制 / *Positioning accuracy currently depends entirely on the density and spatial coverage of manually collected fingerprints during calibration, lacking automatic crowdsourcing or semi-supervised update mechanisms*；
- **仅支持单张楼层平面图** / **Single floor plan only**：`floorplan.svg` 为当前唯一底图，尚不支持多楼层、多建筑的地图动态切换（尽管定位结果本身能够区分楼层与楼栋信息）/ *`floorplan.svg` is the only base map; multi-floor and multi-building dynamic map switching is not yet supported (although the positioning results themselves can distinguish floor and building information)*；
- **KNN 为基准算法** / **KNN as the baseline algorithm**：K=3 的加权 KNN 作为定位基线较为简洁，但在信号波动较大的复杂室内环境中精度有限。后续可考虑引入概率模型（如高斯过程回归、粒子滤波）提升鲁棒性 / *Weighted KNN (K=3) is a simple baseline but has limited accuracy in complex indoor environments with significant signal fluctuations. Future work could introduce probabilistic models (e.g., Gaussian Process regression, particle filters) to improve robustness*；
- **本地化部署** / **Local-only deployment**：所有数据（指纹库、AP 映射）均存储于设备本地，不具备跨设备指纹库共享或云端协同更新的能力 / *All data (fingerprint database, AP mappings) is stored locally on the device, without cross-device fingerprint sharing or cloud-based collaborative updating capabilities.*

### 3.3 适用场景 / Applicable Scenarios

当前版本适用于以下场景的原型验证与小范围试点部署：

*The current version is suitable for prototype validation and small-scale pilot deployment in the following scenarios:*

- 校园教学楼、办公楼内的人员实时定位 / *Real-time personnel positioning in campus teaching buildings and office buildings*；
- 室内导航应用的基础定位能力支撑 / *Foundational positioning capability for indoor navigation applications*；
- Wi-Fi 指纹定位算法的可行性研究与教学演示 / *Feasibility research and educational demonstrations of Wi-Fi fingerprint positioning algorithms.*

---

## 四、项目进展与状态 / 4. Project Status & Background

本项目最初为客户定制开发，目标场景为**客轮上工作人员的手环室内定位**。项目名 "VesselLoc" 即来源于此——Vessel（船舶）+ Loc（定位）。

*This project was originally developed as a custom solution for a client, targeting **indoor wristband positioning for staff on passenger ships**. The project name "VesselLoc" originates from this use case — Vessel + Loc (Location).*

然而，客户在竞标中未能中标，导致包括本应用在内的一系列功能软件被迫中止交付。

*However, the client did not win the bid, resulting in the suspension of delivery for this application along with a suite of related software features.*

**当前状态：** / **Current status:**

- **平台** / **Platform**：基于 Android 原生开发（Kotlin），若后续重启，计划迁移至 Wear OS 以适配手环等可穿戴设备 / *Built on native Android (Kotlin); if resumed, the plan is to migrate to Wear OS to better suit wristband and wearable devices*；
- **测试环境** / **Test environment**：目前导入了办公室的楼层布局数据进行功能验证与算法测试，尚未在实际客轮环境中部署运行 / *Office floor plan data has been imported for functional verification and algorithm testing; the app has not been deployed or tested in an actual passenger ship environment*；
- **维护状态** / **Maintenance status**：已停止主动开发，代码作为技术原型归档。AP 映射、指纹库等数据均为开发期间录入的示例数据 / *Active development has been discontinued; the code is archived as a technical prototype. AP mappings, fingerprint databases, and other data are sample data entered during the development period.*
