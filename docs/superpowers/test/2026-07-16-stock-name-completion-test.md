# 娴嬭瘯鏂规锛欰鑲¤偂绁ㄥ悕绉拌ˉ鍏ㄤ笌浜屾婢勬竻

## 1. 娴嬭瘯鑳屾櫙

### 1.1 瀵瑰簲 Story

- Story 涓庤璁★細`docs/superpowers/plans/2026-07-16-stock-name-completion-design.md`
- 鍓嶇疆 Story锛歚docs/superpowers/plans/2026-08-03-trading-intent-routing-consolidation-story.md`

### 1.2 娴嬭瘯鐩爣

- 楠岃瘉搴旂敤鍚姩鏃朵粠 Tushare 鍏ㄩ噺鐑姞杞借偂绁ㄥ悕绉扮洰褰曪紝骞舵瘡鏃ュ師瀛愬埛鏂?JVM 绱㈠紩銆?
- 楠岃瘉绱㈠紩 7 澶╂湁鏁堟湡銆佸埛鏂板け璐ヤ繚鎶ゅ拰 `NOT_READY/READY/EXPIRED` 鐘舵€佺鍚?Story 濂戠害銆?
- 楠岃瘉绮剧‘ Map銆佽繛缁瓙涓?List銆佸敮涓€鍊欓€夊拰澶氬€欓€変簩娆℃緞娓呰涓烘纭€?
- 楠岃瘉 3201 鍙彁鍙?`stockNameQuery`锛孞ava 瀹屾垚鍊欓€夎В鏋愬拰 Pending 閫夋嫨鏍￠獙銆?
- 楠岃瘉 Story 1 鐨勮韩浠介妫€銆乺un 闅旂銆丼SE 鎵€鏈夋潈鍜岀洿鎺?Trading API 涓嶅彂鐢熷洖褰掋€?

### 1.3 娴嬭瘯鑼冨洿

- `ai-agent-study-domain`锛歚StockSlot`銆?201 Schema/Prompt銆乣StockRequestResolver`銆佽偂绁ㄥ悕绉扮储寮曘€?
  鍙岀淮搴?Pending 棰嗗煙濂戠害銆佺粺涓€璺敱鍜?SSE 缁堟鍗忚銆?
- `ai-agent-study-trading-api`锛氳偂绁ㄧ洰褰曘€佸€欓€夊拰瑙ｆ瀽缁撴灉濂戠害銆?
- `ai-agent-study-trading-domain`锛氬凡瑙ｆ瀽 FULL 璇锋眰涓?`TradingRequestNode` 鍗忎綔銆?
- `ai-agent-study-trading-infra`锛歍ushare 鑲＄エ鐩綍婧愰€傞厤鍣ㄣ€?
- `ai-agent-study-infrastructure`锛歊edis Pending 浠撳偍瀹炵幇銆?
- `ai-agent-study-app`锛氬惎鍔ㄧ儹鍔犺浇銆佽皟搴﹁閰嶅拰璺ㄦā鍧楅泦鎴愩€?

閲嶇偣缁勪欢锛?

- `StockNameIndex`
- `StockNameRefreshService`
- `StockNameResolutionService`
- `StockRequestResolver`
- `StockResolutionPendingRepository`
- `TradingRequestNode`
- `IntentRoutingNode`
- `TargetContextFactory`
- `TradingStarter`

### 1.4 涓嶅湪鏈娴嬭瘯鑼冨洿

- 鎷奸煶銆侀敊鍒瓧銆佺紪杈戣窛绂汇€佽涔夊悜閲忓拰棰濆鍒悕妫€绱€?
- 澶氳偂绁ㄥ悓鏃跺垎鏋愬拰鍖呭惈鑲＄エ鍒嗘瀽鐨勫浠诲姟鎵ц銆?
- 鍊欓€夋寜閽€佺粨鏋勫寲鍓嶇鍗＄墖鎴栨柊鐨勫墠绔姹傚瓧娈点€?
- 琛屾儏銆佽储鍔°€佹柊闂汇€佹儏缁瓑鍔ㄦ€佹暟鎹紦瀛樼瓥鐣ヨ皟鏁淬€?
- 鐪熷疄鐢熶骇 Redis銆佺敓浜?MySQL 鎴栫敓浜?Tushare 浣滀负鑷姩鍖栨祴璇曚緷璧栥€?

---

## 2. 娴嬭瘯绛栫暐

### 2.1 娴嬭瘯鍒嗗眰

| 娴嬭瘯灞傜骇 | 鏄惁瑕嗙洊 | 璇存槑 |
|------|------|------|
| 鍗曞厓娴嬭瘯 | 鏄?| 楠岃瘉绱㈠紩銆佸尮閰嶃€佺姸鎬佹満銆佽繃鏈熷拰閫夋嫨鍒嗘敮 |
| 闆嗘垚娴嬭瘯 | 鏄?| 楠岃瘉 3201 鍒?Trading 鍚姩鍓嶇殑妯″潡鍗忎綔 |
| 鎺ュ彛娴嬭瘯 | 鏄?| 楠岃瘉 AutoAgent SSE 鍜岀洿鎺?Trading API 鍏煎鎬?|
| 鍥炲綊娴嬭瘯 | 鏄?| 楠岃瘉 Story 1銆佹櫘閫氭剰鍥惧拰 Trading pipeline 琛屼负 |
| 鎬ц兘娴嬭瘯 | 鏄?| 楠岃瘉绾?6,000 鏉＄洰褰曠殑鏈湴鏌ヨ寤惰繜 |
| 鎵嬪伐楠岃瘉 | 鏄?| 楠岃瘉鐪熷疄 Tushare 鏁版嵁銆佸畾鏃跺埛鏂板拰鍚?JVM 骞跺彂 Pending |

### 2.2 娴嬭瘯鍘熷垯

- 鑷姩鍖栨祴璇曞浐瀹氫娇鐢?`UNIFIED` 璺敱妯″紡銆?
- Tushare銆丷edis銆佹椂閽熴€佽皟搴﹀櫒銆?201 ChatClient 鍜?Trading pipeline 缁熶竴浣跨敤 mock 鎴栨祴璇曟浛韬€?
- 姣忎釜娴嬭瘯鐢ㄤ緥蹇呴』鏈夋槑纭柇瑷€锛屼笉渚濊禆鍥哄畾 `sleep` 楠岃瘉寮傛琛屼负銆?
- 鏃堕棿鐩稿叧娴嬭瘯娉ㄥ叆 `Clock`锛岀簿纭鐩?7 澶╁拰 10 鍒嗛挓杈圭晫銆?
- 鍘熷瓙鏇挎崲娴嬭瘯浣跨敤骞跺彂灞忛殰鎴栧彲鎺ф墽琛屽櫒锛屼笉渚濊禆鍋剁劧绾跨▼鏃跺簭銆?

### 2.3 Mock 绛栫暐

| 渚濊禆椤?| 鏄惁 Mock | Mock 鏂瑰紡 | 璇存槑 |
|------|------|------|------|
| Tushare 鍏ㄩ噺鎺ュ彛 | 鏄?| Stub/Mockito | 鎺у埗姝ｅ父銆佺┖銆佸皯閲忋€侀潪娉曘€侀噸澶嶅拰寮傚父鍝嶅簲 |
| Tushare 绮剧‘鍚嶇О鍏滃簳 | 鏄?| Stub/Mockito | 鎺у埗鍞竴銆佸鏉°€佺┖鍜屼弗鏍艰皟鐢ㄥ紓甯革紝涓嶅洖鍐欑储寮?|
| 3201 ChatClient | 鏄?| 鍥哄畾缁撴瀯鍖栬緭鍑?| 鍙獙璇?`stockNameQuery` 鍜屾剰鍥炬Ы浣?|
| Redis | 鏄?| 鍐呭瓨 Pending 浠撳偍/Mockito | 楠岃瘉 Key銆乀TL銆佸垹闄ゅ拰寮傚父鍒嗙被 |
| `Clock` | 鏄?| 鍥哄畾鏃堕挓 | 楠岃瘉绱㈠紩鍙?Pending 绮剧‘杩囨湡杈圭晫 |
| 璋冨害鍣?| 鏄?| 鐩存帴璋冪敤鍒锋柊鍏ュ彛 | 涓嶇瓑寰呯湡瀹?cron |
| `TargetContextFactory` | 鏄?| Spy/Stub | 楠岃瘉璋冪敤娆℃暟銆佸弬鏁板拰澶辫触浼犳挱 |
| Trading pipeline | 鏄?| Spy/Stub | 楠岃瘉鏄惁鍒涘缓 run 骞惰繘鍏?Trading |
| SSE sender | 鏄?| 鍐呭瓨鍚屾 sender | 绮剧‘鏂█浜嬩欢绫诲瀷銆侀『搴忓拰娆℃暟 |

---

## 3. 娴嬭瘯鍦烘櫙璁捐

### 3.1 姝ｅ父鍦烘櫙

| 缂栧彿 | 鍦烘櫙鍚嶇О | 鍓嶇疆鏉′欢 | 杈撳叆 | 棰勬湡缁撴灉 | status |
|------|------|------|------|------|------|
| TC-001 | 鍚姩鍏ㄩ噺鐑姞杞?| Tushare 杩斿洖闈炵┖鍚堟硶璁板綍 | 搴旂敤鍚姩 Runner | 鏋勫缓 `READY` 绱㈠紩锛岃褰曟暟銆佸埛鏂版椂闂村拰杩囨湡鏃堕棿姝ｇ‘ | pass |
| TC-002 | 鏍囧噯鍚嶇О绮剧‘鍖归厤 | 绱㈠紩鍖呭惈鍖楁柟鍗庡垱 | `鍖楁柟鍗庡垱` | Map 绮剧‘鍛戒腑骞惰ˉ榻?`002371` | pass |
| TC-003 | 鍚庣紑杩炵画瀛愪覆鍖归厤 | 绱㈠紩鍖呭惈鍖楁柟鍗庡垱 | `鍗庡垱` | List 杩斿洖鍖楁柟鍗庡垱鍊欓€?| pass |
| TC-004 | 鍓嶇紑杩炵画瀛愪覆鍖归厤 | 绱㈠紩鍖呭惈鍗庡垱浜戜俊 | `鍗庡垱` | List 杩斿洖鍗庡垱浜戜俊鍊欓€?| pass |
| TC-005 | 涓棿杩炵画瀛愪覆鍞竴鍖归厤 | 鍙湁涓€涓悕绉板寘鍚洰鏍囩墖娈?| 鍚嶇О涓棿鐗囨 | Java 鑷姩琛ラ綈瑙勮寖鍚嶇О鍜屼唬鐮?| pass |
| TC-006 | 鑲＄エ鍜屾ā寮忓悓鏃朵笉鏄庣‘ | `鍗庡垱` 鍛戒腑涓ゅ彧鑲＄エ | `鍒嗘瀽鍗庡垱` | 淇濆瓨鍙岀淮搴?Pending锛屽彧鍙戦€佽偂绁ㄥ€欓€夋緞娓咃紝涓嶅垱寤?run | pass |
| TC-007 | 搴忓彿閫夋嫨鍚庣户缁緞娓呮ā寮?| Pending 鐨勬ā寮忔湭纭 | `1`銆乣绗竴涓猔 | 纭绗竴鍊欓€夛紝淇濈暀 Pending锛屽啀璇㈤棶蹇€?瀹屾暣 | pass |
| TC-008 | 瀹屾暣鍚嶇О閫夋嫨杩涘叆 QUICK | Pending 宸茶褰?`QUICK` | `鍖楁柟鍗庡垱` | 纭鑲＄エ銆佹竻闄?Pending銆佽繘鍏?`GeneralChatNode` | pass |
| TC-009 | 鍏綅浠ｇ爜閫夋嫨杩涘叆 FULL | Pending 宸茶褰?`FULL` | `002371` | 纭鑲＄エ銆佹竻闄?Pending銆佽繘鍏?`TradingRequestNode` | pass |
| TC-010 | 鏄庣‘浠ｇ爜璺宠繃鍚嶇О绱㈠紩 | 鑲＄エ绱㈠紩涓嶅彲鐢紝韬唤 Provider 鍙敤 | `002371` | 涓嶆煡璇㈠悕绉扮储寮曪紝鐩存帴杩涘叆 `TargetContextFactory` | pass |
| TC-011 | 姣忔棩鍒锋柊鎴愬姛 | 褰撳墠绱㈠紩鍙敤 | 瑙﹀彂鍒锋柊鍏ュ彛 | 鍘熷瓙鍙戝竷鏂扮储寮曞苟閲嶇疆 7 澶╂湁鏁堟湡 | pass |
| TC-012 | 鍒锋柊澶辫触淇濈暀鏃х储寮?| 褰撳墠绱㈠紩鏈繃鏈?| Tushare 鎶涘紓甯?| 鏃х储寮曠户缁湇鍔★紝澶辫触璁℃暟澧炲姞 | pass |
| TC-013 | 鍊欓€夊鍚嶇О鍒囨崲鑲＄エ | session 宸叉湁鈥滄煡鍗庡垱鏄ㄥぉ鏀剁洏浠封€濈殑 Pending 涓斿凡纭鍒嗘瀽妯″紡 | `璐靛窞鑼呭彴` | 灏嗗€欓€夊 `stockNameQuery` 浣滀负鏂拌偂绁ㄦ煡璇紝瑕嗙洊鏃у€欓€夈€佺敓鎴愭柊 version锛屼繚鐣欏師濮嬩笟鍔￠棶棰樺拰宸茬‘璁ゆā寮忥紝骞舵寜璐靛窞鑼呭彴鐨勬柊鏌ヨ缁撴灉缁х画璺敱 | pass |
| TC-014 | 闈炶偂绁ㄦ剰鍥炬竻闄?Pending | session 宸叉湁鑲＄エ Pending | 鏃犳硶鎸?Pending 瑙勫垯纭畾鎬цВ鏋愪笖 3201 鏄庣‘鍒や负 `GENERAL_CHAT` 鐨勮姹?| 鏅€氳矾鐢辨墽琛岋紝鑲＄エ Pending 琚垹闄?| pass |
| TC-015 | 鍞竴鑲＄エ浣嗘ā寮忎笉鏄庣‘ | 鍖楁柟鍗庡垱鍞竴鍛戒腑 | `鍒嗘瀽鍖楁柟鍗庡垱` | 淇濆瓨宸茶В鏋愯偂绁紝鍙闂揩閫?瀹屾暣 | pass |
| TC-016 | 澶氬€欓€変笖妯″紡宸蹭负 QUICK | `鍗庡垱` 鍛戒腑涓ゅ彧鑲＄エ | `绠€鍗曠湅鐪嬪崕鍒沗 | 鍙闂偂绁紝閫夋嫨鍚庤繘鍏?GeneralChat | pass |
| TC-017 | 澶氬€欓€変笖妯″紡宸蹭负 FULL | `鍗庡垱` 鍛戒腑涓ゅ彧鑲＄エ | `瀹屾暣鍒嗘瀽鍗庡垱` | 鍙闂偂绁紝閫夋嫨鍚庤繘鍏?TradingRequestNode | pass |
| TC-018 | 鏈氨缁储寮曡繙绔敮涓€鍏滃簳 | 绱㈠紩涓?`NOT_READY`锛岃繙绔簿纭悕绉拌繑鍥炲敮涓€缁撴灉 | 瀹屾暣鑲＄エ鍚嶇О | Java 琛ラ綈鍚嶇О鍜屼唬鐮侊紝鎸?analysisMode 缁х画璺敱锛屼笉鍒锋柊鏈湴绱㈠紩 | pass |
| TC-019 | 杩囨湡绱㈠紩杩滅澶氬€欓€夊厹搴?| 绱㈠紩涓?`EXPIRED`锛岃繙绔簿纭悕绉拌繑鍥炲鏉?| 瀹屾暣鑲＄エ鍚嶇О | 鍒涘缓鍊欓€?Pending 骞舵緞娓咃紝涓嶄娇鐢ㄨ繃鏈熺储寮?| pass |
| TC-020 | 鏈氨缁储寮曡繙绔┖缁撴灉 | 绱㈠紩涓?`NOT_READY`锛岃繙绔簿纭悕绉拌繑鍥炵┖ | 涓嶅瓨鍦ㄧ殑瀹屾暣鍚嶇О | 杩斿洖鑲＄エ涓嶅瓨鍦紝涓嶅垱寤?Pending 鎴?run | pass |
| TC-021 | 绌鸿偂绁ㄦЫ浣嶄繚鐣欏畬鏁村垎鏋愭ā寮?| 鏃?Pending锛?201 璇嗗埆 `FULL` 浣嗘湭鎻愬彇鍚嶇О鎴栦唬鐮?| `瀹屾暣鍒嗘瀽涓€鍙偂绁╜ | 鍒涘缓 `stockTarget=UNRESOLVED, analysisMode=FULL` Pending锛屽彧璇㈤棶鑲＄エ鍚嶇О鎴栧叚浣嶄唬鐮?| pass |
| TC-022 | 鑲＄エ鍜屾ā寮忛兘涓虹┖ | 鏃?Pending锛屼袱涓淮搴﹀潎鏈В鏋?| 鑲＄エ鐩稿叧浣嗘棤鏍囩殑鐨勮姹?| 鍒涘缓鍙岀淮搴︽湭瑙ｆ瀽 Pending锛屽厛璇㈤棶鑲＄エ锛屼笉璇㈤棶妯″紡 | pass |
| TC-023 | Pending 鏈夋晥鍥炲浼樺厛浜?3201 鎰忓浘璇垽 | session 瀛樺湪鑲＄エ鎴栨ā寮?Pending锛?201 鏈疆杩斿洖 `GENERAL_CHAT` | `1`銆乣绗竴涓猔銆佹湁鏁堟ā寮忔垨鍊欓€夊鏂拌偂绁ㄥ悕绉?浠ｇ爜 | Java 鍏堢‘瀹氭€ф帹杩涙垨瑕嗙洊 Pending锛屼笉娓呴櫎鐘舵€併€佷笉鎸夋櫘閫氳亰澶╁垎鏀墽琛岋紱涓や釜缁村害纭鍚庤繘鍏ュ搴旇妭鐐?| pass |
| TC-024 | QUICK 纭畾鎬ф墽琛?Query 缁勮 | Pending 淇濆瓨鈥滄垜鎯虫煡鍗庡垱鏄ㄥぉ鐨勬敹鐩樹环鈥濓紝Java 宸茶В鏋愬寳鏂瑰崕鍒?`002371` 涓旀ā寮忎负 `QUICK` | 浜屾鍥炲 `1` | 涓嶈皟鐢?LLM 鏀瑰啓锛涙寜鍥哄畾妯℃澘鐢熸垚鍖呭惈鍘熷闂銆佽鑼冨悕绉板拰浠ｇ爜鐨?`executionQuery`锛沗GeneralChatNode` 鍙帴鏀惰鏅€氭枃鏈笖涓嶈鍙?`StockSlot` | pass |

### 3.2 寮傚父鍦烘櫙

| 缂栧彿 | 鍦烘櫙鍚嶇О | 鍓嶇疆鏉′欢 | 杈撳叆 | 棰勬湡缁撴灉 | status |
|------|------|------|------|------|------|
| TC-101 | 棣栨鍔犺浇璋冪敤澶辫触 | 灏氭棤鍙敤绱㈠紩 | Tushare 瓒呮椂 | 鐘舵€佷负 `NOT_READY`锛屽簲鐢ㄥ彲鍚姩锛涗笉瀹夋帓棰濆鍒锋柊閲嶈瘯锛屽彧绛夊緟姣忔棩 `03:30` 浠诲姟锛涘悗缁悕绉拌姹備娇鐢ㄨ姹傜骇杩滅鍏滃簳 | pass |
| TC-102 | 棣栨鍔犺浇杩斿洖绌哄垪琛?| 灏氭棤鍙敤绱㈠紩 | 绌烘暟鎹?| 鎷掔粷鍙戝竷锛岀姸鎬佷负 `NOT_READY` | pass |
| TC-103 | 鍗曟鍏ㄩ噺鎺ュ彛璋冪敤 | 瑙﹀彂鍚姩鎴栧畾鏃跺埛鏂?| 鎷夊彇涓婂競鑲＄エ鐩綍 | 鍙皟鐢ㄤ竴娆?`stock_basic(list_status=L)`锛屼笉浼?`limit/offset`锛屼笉鎸変氦鏄撴墍鎷嗗垎 | pass |
| TC-104 | 婧愪唬鐮佹牸寮忛潪娉?| 鍒锋柊鏁版嵁鍖呭惈闈炴硶 `ts_code` | 闈炴硶鎵规 | 鎷掔粷鍙戝竷鏁翠釜鎵规 | pass |
| TC-105 | 鑲＄エ浠ｇ爜閲嶅 | 鍒锋柊鏁版嵁鍚噸澶嶅叚浣嶄唬鐮?| 閲嶅鎵规 | 鎷掔粷鍙戝竷锛屼笉闈欓粯瑕嗙洊 | pass |
| TC-106 | 鑲＄エ鍚嶇О涓虹┖ | 鍒锋柊鏁版嵁鍚┖鍚嶇О | 闈炴硶鎵规 | 鎷掔粷鍙戝竷鏁翠釜鎵规 | pass |
| TC-107 | 鍚嶇О鏃犲€欓€?| 绱㈠紩 `READY` | 涓嶅瓨鍦ㄧ殑鍚嶇О鐗囨 | 涓氬姟缁撴灉涓?`NOT_FOUND`锛岄€氳繃 `CLARIFICATION` 鍗忚鏄庣‘鍥炲鑲＄エ涓嶅瓨鍦紱涓嶅垱寤?Pending銆乺un锛屼篃涓嶈拷闂垎鏋愭ā寮?| pass |
| TC-108 | Pending 搴忓彿瓒婄晫 | Pending 鍙湁涓や釜鍊欓€?| `3` | 淇濈暀 Pending 骞跺啀娆℃緞娓?| pass |
| TC-109 | Pending 鍊欓€夊浠ｇ爜鍒囨崲鐩爣 | Pending 涓嶅惈鎸囧畾浠ｇ爜涓斿凡璁板綍 `FULL` | 鍏朵粬鍚堟硶鍏綅浠ｇ爜 | 鍘熷瓙瑕嗙洊鏃у€欓€夊苟鐢熸垚鏂?version锛屼繚鐣?`FULL`锛屾潈濞侀妫€鏂颁唬鐮佸悗杩涘叆 `TradingRequestNode` | pass |
| TC-110 | Pending 宸茶繃鏈?| Redis Key 宸茶繃鏈?| `绗竴涓猔 | 鎻愮ず閲嶆柊杈撳叆鑲＄エ鍚嶇О锛屼笉鍒涘缓 run | pass |
| TC-111 | Redis 鍐欏叆澶辫触 | 澶氬€欓€夐渶瑕佸垱寤?Pending | `鍒嗘瀽鍗庡垱` | 杩斿洖 `ERROR`锛屼笉鍙戦€佸彲缁х画閫夋嫨鐨勫亣婢勬竻 | pass |
| TC-112 | Redis 璇诲彇澶辫触 | session 鍙兘瀛樺湪 Pending | 浜屾鍥炲 | 杩斿洖 `ERROR`锛屼笉鐚滄祴鍊欓€?| pass |
| TC-113 | 绱㈠紩杩囨湡 | 鏈€杩戞垚鍔熷埛鏂板凡婊?7 澶?| 鍚嶇О鏌ヨ | 涓嶄娇鐢ㄨ繃鏈熺储寮曪紝鏀硅蛋 Java 杩滅绮剧‘鍚嶇О鍏滃簳 | pass |
| TC-114 | 韬唤棰勬鏈壘鍒?| 鍚嶇О鍞竴瑙ｆ瀽鎴愬姛 | 鏉冨▉鏌ヨ杩斿洖绌?| 澶嶇敤 Story 1 `CLARIFICATION`锛屼笉鍚姩 Trading | pass |
| TC-115 | 韬唤 Provider 澶辫触 | 鍚嶇О鍞竴瑙ｆ瀽鎴愬姛 | Provider 鎶涘紓甯?| 澶嶇敤 Story 1 `ERROR`锛屼繚鐣?cause | pass |
| TC-116 | 鍚?JVM 骞跺彂閫夋嫨 | 涓や釜璇锋眰绾跨▼璇诲彇鍚屼竴 version | 鍚屾椂鍥炲鍚屼竴鏈夋晥閫夋嫨 | 鍙湁涓€涓?Claim 鎴愬姛骞惰繘鍏ユ墽琛岃妭鐐?| pass |
| TC-117 | 閮ㄥ垎鎺ㄨ繘 CAS 鍐茬獊 | Pending 宸茶鍙︿竴璇锋眰鏇存柊 | 浣跨敤鏃?version 鏇存柊 | CAS 澶辫触锛岄噸鏂拌鍙栵紝涓嶈鐩栨柊鐘舵€?| pass |
| TC-118 | 鏃ц姹傚畬鎴愬垹闄ゆ柊 Pending | 鏃ц姹傚凡 Claim锛岄殢鍚庢柊鏌ヨ瑕嗙洊 | 鏃ц姹傚畬鎴愬洖璋?| version/claimId 涓嶅尮閰嶏紝涓嶅垹闄ゆ柊 Pending | pass |
| TC-119 | Claim 鍐欏叆澶辫触 | 涓や釜缁村害宸茬‘璁?| Redis Lua 澶辫触 | 杩斿洖 `ERROR`锛屼笉杩涘叆浠讳綍鎵ц鑺傜偣 | pass |
| TC-120 | 鎺ョ鍓嶅け璐ラ噴鏀?| Claim 鎴愬姛浣嗘墽琛岃妭鐐规湭鎺ョ | 鍙噸璇曠郴缁熼敊璇?| 鎸?claimId 鎭㈠ PENDING 骞剁敓鎴愭柊 version | pass |
| TC-121 | Tushare API 涓氬姟閿欒 | 涓ユ牸璋冪敤鏀跺埌 `code=40101` | 閿欒 Token 鍝嶅簲 | 鎶?`TushareApiException` 骞朵繚鐣?code銆乵sg銆乤piName | pass |
| TC-122 | Tushare 浼犺緭閿欒 | 涓ユ牸璋冪敤鎵ц HTTP | SSL銆佽繛鎺ユ垨瓒呮椂寮傚父 | 鎶?`TushareTransportException` 骞朵繚鐣?cause | pass |
| TC-123 | Tushare 鍗忚閿欒 | HTTP 鎴愬姛 | 闈炴硶 JSON 鎴栧繀瑕佺粨鏋勭己澶?| 鎶?`TushareProtocolException` | pass |
| TC-124 | 鍏ㄩ噺鎺ュ彛姝ｅ父绌烘暟鎹?| `code=0, items=[]` | 鍚姩鎴栧埛鏂?| Client 杩斿洖绌哄垪琛紝鍒锋柊鏈嶅姟鎷掔粷鍙戝竷骞跺垎绫讳负鏁版嵁寮傚父 | pass |
| TC-125 | 鏃ц皟鐢ㄥ吋瀹?| 浣跨敤鍘?`callGeneric()` | API銆佺綉缁滄垨瑙ｆ瀽閿欒 | 缁存寔鏃ュ織鍔犵┖鍒楄〃闄嶇骇璇箟 | pass |
| TC-126 | 璇锋眰绾ц繙绔厹搴曞け璐?| 绱㈠紩涓?`NOT_READY/EXPIRED` | 杩滅 API銆佷紶杈撴垨鍗忚寮傚父 | 杩斿洖 `ERROR` 骞朵繚鐣欏紓甯稿垎绫伙紝涓嶄吉瑁呮垚鑲＄エ涓嶅瓨鍦?| pass |

### 3.3 杈圭晫鍦烘櫙

| 缂栧彿 | 鍦烘櫙鍚嶇О | 鍓嶇疆鏉′欢 | 杈撳叆 | 棰勬湡缁撴灉 | status |
|------|------|------|------|------|------|
| TC-201 | 閲嶅鏍囧噯鍚嶇О | 涓や釜浠ｇ爜鎷ユ湁鐩稿悓鏍囧噯鍚嶇О | 瀹屾暣鍚嶇О | Map 杩斿洖涓ゆ潯骞惰繘鍏ユ緞娓咃紝涓嶈鐩栬褰?| pass |
| TC-202 | 鏌ヨ鍖呭惈绌虹櫧 | 绱㈠紩鍙敤 | ` 鍗?鍒?` | 瑙勮寖鍖栧悗鎸?`鍗庡垱` 鏌ヨ | pass |
| TC-203 | 鎷変竵瀛楁瘝澶у皬鍐?| 鍖呭惈 ST 鍚嶇О | 澶у皬鍐欎笉鍚岃緭鍏?| NFKC 鍜屽ぇ鍐欒鑼冨寲鍚庢纭尮閰?| pass |
| TC-204 | ST 鏈夋晥瀛楃淇濈暀 | 绱㈠紩鍚?`*ST` 鍚嶇О | `*ST` 鐗囨 | 鏄熷彿鍜?ST 涓嶈鍒犻櫎 | pass |
| TC-205 | 鍗曞瓧绗﹀ぇ閲忓€欓€?| 澶ч噺鍚嶇О鍖呭惈鍚屼竴瀛楃 | 鍗曞瓧绗?| 鎵弿瀹屾暣闆嗗悎锛屽彧杩斿洖鍊欓€変笂闄愬苟鎶ュ憡鎬绘暟锛屼笉鍒涘缓 Pending | pass |
| TC-206 | 绮剧‘鍖归厤浼樺厛 | 瀹屾暣鍚嶇О鍚屾椂鏄叾浠栧悕绉板瓙涓?| 瀹屾暣鏍囧噯鍚嶇О | 鍙繑鍥炵簿纭?Map 缁撴灉锛屼笉杩涘叆妯＄硦鎵弿 | pass |
| TC-207 | 鍊欓€夌ǔ瀹氭帓搴?| 澶氬€欓€夎緭鍏ラ『搴忛殢鏈?| 鐩稿悓鏌ヨ閲嶅鎵ц | 鍊欓€夐『搴忓缁堟寜鍚嶇О鍜屼唬鐮佺ǔ瀹?| pass |
| TC-208 | 绱㈠紩鍒氬ソ鏈弧 7 澶?| 鍥哄畾鏃堕挓涓鸿繃鏈熷墠 1 ms | 鍚嶇О鏌ヨ | 绱㈠紩浠嶅彲鐢?| pass |
| TC-209 | 绱㈠紩鍒氬ソ杈惧埌 7 澶?| 鍥哄畾鏃堕挓绛変簬 `expiresAt` | 鍚嶇О鏌ヨ | 鐘舵€佷负 `EXPIRED` | pass |
| TC-210 | Pending 鍒氬ソ鏈弧 10 鍒嗛挓 | 鍥哄畾鏃堕挓涓鸿繃鏈熷墠 1 ms | 鏈夋晥閫夋嫨 | 閫夋嫨鎴愬姛 | pass |
| TC-211 | Pending 鍒氬ソ杈惧埌 10 鍒嗛挓 | 鍥哄畾鏃堕挓绛変簬杩囨湡鏃堕棿 | 鏈夋晥閫夋嫨鏂囨湰 | 鎸夎繃鏈熷鐞?| pass |
| TC-212 | JVM 鍒锋柊閲嶅彔 | 鍚姩銆佸畾鏃跺叆鍙ｅ苟鍙戣Е鍙?| 涓や釜鍒锋柊璋冪敤 | 鍙湁涓€涓繙绔叏閲忚皟鐢ㄦ墽琛?| pass |
| TC-213 | 鍒锋柊鏈熼棿骞跺彂璇诲彇 | 鏃х储寮曞彲鐢紝鏂扮储寮曟瀯寤轰腑 | 杩炵画鏌ヨ | 姣忔鍙瀵熷畬鏁存棫鐗堟垨瀹屾暣鏂扮増 | pass |
| TC-214 | 绾?6,000 鏉℃煡璇㈡€ц兘 | JVM 瀹屾垚棰勭儹 | 10,000 娆℃贩鍚堟煡璇?| P95 灏忎簬 5 ms锛孭99 灏忎簬 10 ms | pass |
| TC-215 | Claim 瓒呮椂閲嶆柊棰嗗彇 | Claim 宸茶秴杩?60 绉?| 鍚屼竴 JVM 鐨勫悗缁姹傞噸璇?| 鍘熷瓙鏇挎崲 claimId锛屽彧鏈夋柊璇锋眰鑾峰緱鎵ц鏉?| pass |
| TC-216 | 鏈夋晥鎺ㄨ繘鍒锋柊 TTL | Pending 鎺ヨ繎杩囨湡 | 鏈夋晥鑲＄エ鎴栨ā寮忛€夋嫨 | CAS 鎴愬姛骞舵妸 TTL 鍒锋柊涓?10 鍒嗛挓 | pass |
| TC-217 | 鏃犳晥閫夋嫨涓嶅埛鏂?TTL | Pending 鎺ヨ繎杩囨湡 | 闈炴硶搴忓彿 | 缁х画婢勬竻浣?TTL 涓嶅欢闀?| pass |
| TC-218 | 閲嶅瀹屾垚骞傜瓑 | Claim 宸插畬鎴愬垹闄?| 鍐嶆 complete | 涓嶆姤閿欍€佷笉褰卞搷鍏朵粬 Pending | pass |
| TC-219 | 棣栨澶辫触鍚庢仮澶?| 绱㈠紩涓?`NOT_READY` | 鍚庣画鍏ㄩ噺鍒锋柊鎴愬姛 | 鍘熷瓙鍙戝竷鏂扮储寮曪紝鐘舵€佸彉涓?`READY`锛岃缃柊鐨?`loadedAt/expiresAt` | pass |
| TC-220 | 杩囨湡鍚庢仮澶?| 绱㈠紩涓?`EXPIRED` | 鍚庣画鍏ㄩ噺鍒锋柊鎴愬姛 | 涓嶈姹傞噸鍚紝鍘熷瓙鍙戝竷鏂扮储寮曞苟鎭㈠ `READY`锛屾湁鏁堟湡閲嶆柊璁＄畻涓?7 澶?| pass |
| TC-221 | 鍙敤绱㈠紩闆跺€欓€変笉杩滅閲嶆煡 | 绱㈠紩涓?`READY` | 鏈湴鍚嶇О鏌ヨ闆跺€欓€?| 杩斿洖鑲＄エ涓嶅瓨鍦紝杩滅鍚嶇О鏌ヨ璋冪敤娆℃暟涓?0 | pass |
| TC-222 | 璇锋眰绾у厹搴曚笉鍥炲啓绱㈠紩 | 绱㈠紩涓?`NOT_READY/EXPIRED` | 杩滅鍚嶇О鏌ヨ鎴愬姛 | 鍙繑鍥炲綋鍓嶈姹傜粨鏋滐紝绱㈠紩鍐呭銆佺姸鎬佸拰鏃堕棿鎴冲潎涓嶅彉鍖?| pass |
| TC-223 | 鍊欓€夊浠ｇ爜鍒囨崲鍚庣户缁緞娓呮ā寮?| Pending 鑲＄エ澶氬€欓€変笖妯″紡鏈‘璁?| 鍊欓€夊鍚堟硶鍏綅浠ｇ爜 | 瑕嗙洊鑲＄エ鍊欓€夊苟淇濈暀 `analysisMode=UNRESOLVED`锛岀‘璁ゆ柊鑲＄エ鍚庡彧璇㈤棶蹇€?瀹屾暣 | pass |
| TC-224 | 鍊欓€夊鍚嶇О閲嶆柊瑙ｆ瀽 | Pending 瀛樺湪澶氫釜鑲＄エ鍊欓€変笖宸茶褰?`FULL` | 鍊欓€夊垪琛ㄥ鐨勮偂绁ㄥ悕绉?| 瑕嗙洊鏃у€欓€夊苟鐢熸垚鏂?version锛屼繚鐣?`originalQuery` 鍜?`FULL`锛涙柊鍚嶇О鍞竴鏃惰В鏋愪负鏂拌偂绁紝澶氬€欓€夋椂鍙戝竷鏂?Pending锛? 鍊欓€夋椂杩斿洖鑲＄エ涓嶅瓨鍦ㄤ笖涓嶆仮澶嶆棫鍊欓€?| pass |
| TC-225 | 鍚?session 澶氭爣绛鹃〉瑕嗙洊 | 鏍囩椤?A 宸插垱寤哄崕鍒?Pending | 鍚?session 鐨勬爣绛鹃〉 B 鍒涘缓骞冲畨 Pending锛岄殢鍚?A 鍥炲 `1` | B 鐨勬柊 version 瑕嗙洊 A锛沗1` 鍙寜褰撳墠骞冲畨 Pending 瑙ｉ噴锛屾棫 version 涓嶅緱淇敼鎴栧垹闄ゅ綋鍓嶇姸鎬?| pass |
| TC-226 | 涓嶅悓 session 澶氭爣绛鹃〉闅旂 | 涓や釜鏍囩椤典娇鐢ㄤ笉鍚?session | 鍒嗗埆鍒涘缓骞堕€夋嫨涓嶅悓鑲＄エ Pending | 涓や唤 Pending 鐙珛鎺ㄨ繘锛屼簰涓嶈鐩栨垨娑堣垂 | pass |
| TC-227 | Pending 闃舵绌烘Ы浣嶅洖澶?| 宸叉湁鑲＄エ鎴栨ā寮忔緞娓?Pending锛孴TL 鎺ヨ繎杩囨湡 | 3201 鏈彁鍙栧悕绉般€佷唬鐮佹垨鏈夋晥妯″紡 | 淇濈暀褰撳墠 Pending 骞堕噸澶嶅綋鍓嶆緞娓咃紝涓嶆煡璇㈡湰鍦版垨杩滅鏁版嵁锛屼笉鍒锋柊 TTL | pass |

### 3.4 鍥炲綊鍦烘櫙

| 缂栧彿 | 鍦烘櫙鍚嶇О | 鍓嶇疆鏉′欢 | 杈撳叆 | 棰勬湡缁撴灉 | status |
|------|------|------|------|------|------|
| TC-301 | AutoAgent 3201 鍞竴鎰忓浘鍏ュ彛 | `UNIFIED` 妯″紡 | AutoAgent 鍚嶇О鑲＄エ璇锋眰 | 鍙墽琛屼竴娆?3201 鎰忓浘璇嗗埆锛屼笉鎭㈠ 6001锛涗笉褰卞搷鐩存帴 Trading API | pass |
| TC-302 | 3201 涓嶈閰嶅悕绉板伐鍏?| Story 2 閰嶇疆鐢熸晥 | 鏋勫缓 3201 | 涓嶅惈 `read_skill` 鍜?`search_stock_by_name` | pass |
| TC-303 | TradingRequestNode 韬唤棰勬 | 鑲＄エ宸茬‘璁や笖妯″紡涓?FULL | 鑲＄エ璇锋眰 | Coordinator 鍒嗘淳鍚庤皟鐢?`TargetContextFactory` 涓€娆?| pass |
| TC-304 | 姣忔璇锋眰鏂?run | 鍚屼竴 session 杩炵画涓ゆ閫夋嫨鑲＄エ | 涓ゆ鏈夋晥璇锋眰 | 涓ゆ `runId` 涓嶅悓锛孴rading 鐘舵€佷笉浜ゅ弶 | pass |
| TC-305 | 涓讳細璇濆巻鍙插鐢?| 鍓嶄竴杞彂閫佸€欓€夋緞娓?| 浜屾鍥炲 | 3201 鍙鍙栧巻鍙诧紝Root 鎸佷箙鍖栨枃鏈笉鍙?| pass |
| TC-306 | StockInfo 鍒濆鍖栧吋瀹?| 韬唤棰勬鎴愬姛 | 鍚姩 Trading | `populateStockInfo()` 浠嶈皟鐢ㄤ竴娆″苟鍐欏叆涓婁笅鏂?| pass |
| TC-307 | 鍘熷鏁版嵁缂撳瓨濂戠害鍏煎 | 鍚岃偂绁ㄤ笉鍚?run | 妫€鏌ョ紦瀛?Key 宸ュ巶涓庨厤缃?| 鏃㈡湁 Key 鍜?TTL 涓嶅鍔?runId锛涗笉瑕佹眰璇佹槑褰撳墠 Provider 宸蹭骇鐢熺紦瀛樺懡涓?| pass |
| TC-308 | 鐩存帴 Trading API 鍏煎 | 鐩存帴鎺ュ彛鍙敤 | 鏄庣‘浠ｇ爜璇锋眰 | 涓嶇粡杩囧悕绉扮储寮曪紝鍘熻緭鍏ヨ緭鍑轰笉鍙?| pass |
| TC-309 | exchange 杈圭晫鍏煎 | Provider 杩斿洖浜ゆ槗鎵€ | 鎶ュ憡鍜屽鍑?| `TargetContext.targetId` 鏉冨▉锛屽睍绀哄瓧娈典粛淇濈暀 | pass |
| TC-310 | analysisDepth 杩介棶鍏煎 | 鑲＄エ宸茬‘璁ゃ€佸垎鏋愭ā寮忕己澶?| 鍚庣画琛ュ厖娣卞害 | QUICK 杩涘叆 GeneralChat锛孎ULL 杩涘叆 TradingRequestNode | pass |
| TC-311 | 鑲＄エ澶氫换鍔￠棬绂?| 璇锋眰鍚偂绁ㄥ垎鏋愬拰鍏朵粬浠诲姟 | 澶氫换鍔¤姹?| 鏁磋疆浠嶆寜 Story 1 鎷掔粷锛屼笉鍒涘缓 Pending 鎴?run | pass |
| TC-312 | 鏅€氭剰鍥惧洖褰?| 鏃犺偂绁ㄦ剰鍥?| GENERAL_CHAT銆丳E銆佸贰妫€ | 鍘熻矾鐢卞拰鎵ц琛屼负涓嶅彉 | pass |
| TC-313 | 鍒嗘瀽鑺傜偣宸ュ叿鍏煎 | 6002-6013 宸茶閰?| 搴旂敤鍚姩 | 鏃㈡湁宸ュ叿闆嗗悎淇濇寔涓嶅彉 | pass |
| TC-314 | SSE 鎵€鏈夋潈鍏煎 | 澶氬€欓€夌粓姝㈣矾鐢?| AutoAgent 璇锋眰 | 涓€娆?clarification銆佷竴娆?complete锛屽灞傚彧鍏抽棴涓€娆?emitter | pass |
| TC-315 | 妯″潡渚濊禆鏂瑰悜 | 璇诲彇妯″潡 POM 鍜?Spring Bean 渚濊禆 | 缂栬瘧涓庝笂涓嬫枃鍚姩 | domain 涓嶅弽鍚戜緷璧栧疄鐜版ā鍧楋紝Resolver 涓嶇洿鎺ヤ緷璧栨墽琛岃妭鐐?| pass |

---

## 4. 鐢ㄤ緥涓庝唬鐮佹槧灏?

| 娴嬭瘯缂栧彿 | 瀵瑰簲鐢ㄤ緥鏂规硶 | 鐩爣绫?鏂规硶 | 瑕嗙洊绫诲瀷 | 璇存槑 |
|------|------|------|------|------|
| TC-001銆?01~106銆?08~213銆?19~220 | `should_publish_only_valid_complete_index_when_refresh_finishes()` 绛?| `StockNameRefreshServiceTest`銆乣StockNameIndexHolderTest` | 姝ｅ父/寮傚父/杈圭晫 | 鐑姞杞姐€佸埛鏂般€佹湁鏁堟湡銆佹仮澶嶅拰鍘熷瓙鎬?|
| TC-002~005銆?07銆?01~207銆?14 | `should_scan_fuzzy_records_when_exact_name_is_absent()` 绛?| `StockNameIndexTest`銆乣StockNameResolutionServiceTest` | 姝ｅ父/寮傚父/杈圭晫 | 绮剧‘鍜岃繛缁瓙涓插尮閰?|
| TC-006~009銆?15~024銆?08~113銆?26銆?10~211銆?21~227 | `should_build_quick_execution_query_without_llm_rewrite()` 绛?| `StockRequestResolverTest`銆乣StockNameResolutionServiceTest`銆乣StockResolutionPendingRepositoryTest` | 姝ｅ父/寮傚父/杈圭晫 | Pending 纭畾鎬ф帴绠°€丵UICK 鎵ц Query銆佺┖妲戒綅銆佸弻缁村害 Pending銆佸€欓€夊鍚嶇О/浠ｇ爜鍒囨崲銆佸鏍囩椤佃竟鐣屻€佽繙绔厹搴曞拰鎵ц鑺傜偣閫夋嫨 |
| TC-010銆?14~115銆?03~304銆?06 | `should_start_trading_only_after_name_and_identity_resolution()` 绛?| `TradingRequestNodeTest`銆乣TargetContextFactoryTest` | 姝ｅ父/寮傚父/鍥炲綊 | Trading 鍓嶇疆韬唤杈圭晫 |
| TC-014銆?01~302銆?05銆?10~314 | `should_keep_single_routing_and_terminal_sse_ownership()` 绛?| `IntentRoutingNodeTest`銆乣RoutingResultHandlerTest`銆乣AiClientNodeToolIsolationTest` | 鍥炲綊 | Story 1 璺敱鍜?SSE 鍏煎 |
| TC-307~309 | `should_preserve_existing_trading_data_and_direct_api_contracts()` 绛?| `TradingStarterPipelineTest`銆乣TradingAnalysisControllerTest` | 鍥炲綊 | Trading 鍐呴儴涓庣洿鎺ュ叆鍙ｅ吋瀹?|
| TC-001銆?06~010銆?18~024銆?01~306銆?14 | `should_complete_stock_name_resolution_before_new_trading_run()` | `StockNameCompletionIntegrationTest` | 闆嗘垚 | 绔埌绔牳蹇冮摼璺€丳ending 鎺ョ浼樺厛绾с€丵UICK Query 缁勮銆佺┖妲戒綅涓庣储寮曚笉鍙敤鍏滃簳 |
| TC-315 | `should_keep_stock_resolution_module_dependencies_acyclic()` | 妯″潡 POM 闈欐€佹祴璇曘€丼pring 涓婁笅鏂囨祴璇?| 鍥炲綊 | Maven 涓?Bean 渚濊禆鏂瑰悜 |
| TC-116~120銆?15~218 | `should_allow_only_one_claim_for_the_same_pending_version()` 绛?| `RedisStockResolutionPendingRepositoryTest` | 寮傚父/杈圭晫 | version CAS銆丆laim銆侀噴鏀惧拰骞傜瓑瀹屾垚 |
| TC-121~126 | `should_distinguish_empty_data_from_api_transport_and_protocol_errors()` 绛?| `TushareApiClientTest`銆乣StockNameRefreshServiceTest`銆乣StockNameResolutionServiceTest` | 寮傚父/鍥炲綊 | 鍒锋柊銆佽繙绔厹搴曠殑涓ユ牸璋冪敤涓庢棫鍏ュ彛鍏煎 |

---

## 5. 鍏抽敭鏍￠獙鐐?

### 5.1 鏁版嵁姝ｇ‘鎬?

- 鐩綍涓氬姟璁板綍鍙寘鍚?`stockName` 鍜屽叚浣?`stockCode`銆?
- 绮剧‘ Map 鍜屾ā绯?List 寮曠敤鐩稿悓璁板綍锛屼笉闈欓粯瑕嗙洊閲嶅鍚嶇О銆?
- `StockSlot.stockNameQuery` 涓嶄綔涓烘渶缁堣偂绁ㄨ韩浠斤紝瑙ｆ瀽鍚庡繀椤诲悓鏃跺瓨鍦ㄨ鑼冨悕绉板拰浠ｇ爜銆?
- 鏈€缁?Trading 韬唤濮嬬粓鏉ヨ嚜 `TargetContext.targetId`銆?
- QUICK 鍜?FULL 鍏辩敤鍚屼竴浠借鑼冭偂绁ㄨВ鏋愮粨鏋滐紝鍙湁 FULL 鍒涘缓 `TargetContext`銆?
- QUICK 鐢?Java 鍥哄畾妯℃澘鐢熸垚 `executionQuery`锛屼繚鐣?`originalQuery` 鐨勪笟鍔¤姹傦紝涓嶈皟鐢?LLM 鏀瑰啓锛?
  `GeneralChatNode` 涓嶈鍙?`StockSlot` 鎴?Pending銆?

### 5.2 鐘舵€佹祦杞纭€?

- 绱㈠紩鍏佽 `NOT_READY -> READY`銆乣READY -> READY`銆乣READY -> EXPIRED` 鍜?
  `EXPIRED -> READY`锛涙墍鏈夋垚鍔熷彂甯冮兘閲嶇疆 `loadedAt/expiresAt`銆?
- 澶氬€欓€夊拰鏃犲€欓€夐樁娈典笉鍒涘缓 `runId`銆?
- 鑲＄エ閫夋嫨鎴愬姛浣嗗垎鏋愭ā寮忔湭纭鏃剁户缁繚鐣?Pending锛屼笉鍒涘缓 `runId`銆?
- 涓や釜缁村害鍏ㄩ儴纭鍚庡厛 Claim锛屽苟鍦ㄨ妭鐐规帴绠″悗鍒犻櫎锛涜鐩栥€佽繃鏈熷拰闈炶偂绁ㄨ浆鍚戞寜濂戠害娓呯悊 Pending銆?
- 娲昏穬 Pending 鐨勬湁鏁堢粨鏋勫寲鍥炲鐢?Java 鍦ㄦ墽琛岃妭鐐归€夋嫨鍓嶄紭鍏堟帴绠★紱鍙湁鏃犳硶纭畾鎬цВ鏋愪笖 3201
  鏄庣‘涓洪潪鑲＄エ鎰忓浘鏃舵墠娓呴櫎 Pending銆?
- 姣忔鏈夋晥 Trading 璇锋眰鍒涘缓鏂扮殑 `runId`銆?

### 5.3 寮傚父澶勭悊姝ｇ‘鎬?

- 鏃犲€欓€夎繑鍥?`NOT_FOUND` 涓氬姟缁撴灉锛屽苟閫氳繃 `CLARIFICATION` 鍗忚鏄庣‘鍥炲鑲＄エ涓嶅瓨鍦紱闈炴硶閫夋嫨鍜?
  Pending 杩囨湡杩斿洖鍙户缁緭鍏ョ殑 `CLARIFICATION`銆?
- 绱㈠紩涓嶅彲鐢ㄦ椂鍏堣蛋璇锋眰绾ц繙绔簿纭悕绉板厹搴曪紱鍙湁鍏滃簳澶辫触鍜?Redis 鏁呴殰杩斿洖 `ERROR`銆?
- 鍒锋柊澶辫触涓嶈兘娓呯┖鏈繃鏈熸棫绱㈠紩銆?
- 韬唤棰勬澶辫触缁х画浣跨敤 Story 1 鐨勪笁绫婚鍩熷紓甯搞€?

### 5.4 鏃ュ織銆佺洃鎺т笌鍛婅

- 鏄惁闇€瑕佹牎楠屾棩蹇楄緭鍑猴細鏄€?
- 鍏抽敭鏃ュ織鍜屾寚鏍囷細绱㈠紩鐘舵€併€佽褰曟暟銆佸埛鏂拌€楁椂銆佺储寮曞勾榫勩€佸埛鏂板け璐ユ鏁般€佹煡璇㈣€楁椂銆佸€欓€夋暟銆?
  Pending 鍒涘缓/鍛戒腑/杩囨湡娆℃暟銆乻essionId銆乺unId 鍜?targetId銆?
- 鏃ュ織涓嶅緱璁板綍 Tushare Token 鎴栧畬鏁?Redis 鍊笺€?

### 5.5 Story 楠屾敹瑕嗙洊鐭╅樀

| Story 楠屾敹椤?| 瑕嗙洊娴嬭瘯 | status |
|------|------|------|
| AC-001~AC-005 | TC-001~TC-005銆乀C-103銆乀C-201~TC-207 | pass |
| AC-006~AC-008 | TC-005~TC-009銆乀C-107~TC-110 | pass |
| AC-009~AC-012 | TC-011~TC-012銆乀C-101~TC-106銆乀C-212~TC-213 | pass |
| AC-013~AC-014 | TC-006~TC-009銆乀C-108~TC-112銆乀C-301~TC-303 | pass |
| AC-015~AC-019 | TC-001銆乀C-011~TC-012銆乀C-101~TC-106銆乀C-113銆乀C-208~TC-209 | pass |
| AC-020~AC-022 | TC-006~TC-010銆乀C-013~TC-014銆乀C-108~TC-112銆乀C-210~TC-211銆乀C-314 | pass |
| AC-023 | TC-214 | pass |
| AC-024~AC-026 | TC-006~TC-009銆乀C-015~TC-017銆乀C-303銆乀C-310 | pass |
| AC-027 | TC-315 | pass |
| AC-028 | TC-116~TC-120銆乀C-215~TC-218 | pass |
| AC-029~AC-030 | TC-121~TC-125 | pass |
| AC-031 | TC-107 | pass |
| AC-032 | TC-006~TC-009銆乀C-015~TC-017 | pass |
| AC-033 | TC-219~TC-220 | pass |
| AC-034 | TC-101 | pass |
| AC-035 | TC-018~TC-020銆乀C-113銆乀C-126 | pass |
| AC-036 | TC-221~TC-222 | pass |
| AC-037 | TC-109銆乀C-118銆乀C-223~TC-224 | pass |
| AC-038 | TC-225~TC-226 | pass |
| AC-039 | TC-021~TC-022銆乀C-217銆乀C-227 | pass |
| AC-040 | TC-023 | pass |
| AC-041 | TC-024 | pass |

---

## 6. 鎵ц璁″垝

### 6.1 鑷姩鍖栨祴璇曟墽琛?

| 姝ラ | 鍐呭 | 棰勬湡缁撴灉 | status |
|------|------|------|------|
| 1 | 琛ュ厖 trading-api 濂戠害鍜?trading-infra 绱㈠紩/鍒锋柊娴嬭瘯 | 鏁版嵁涓庣敓鍛藉懆鏈熷垎鏀€氳繃 | pass |
| 2 | 琛ュ厖 trading-domain 瑙ｆ瀽銆丳ending 鍜岃姹傝妭鐐规祴璇?| 鍞竴銆佸鍊欓€夊拰寮傚父鍒嗘敮閫氳繃 | pass |
| 3 | 琛ュ厖 domain 璺敱銆佸伐鍏烽殧绂诲拰 SSE 鍥炲綊娴嬭瘯 | Story 1 杈圭晫鏃犲洖褰?| pass |
| 4 | 琛ュ厖 app 鍚姩鐑姞杞戒笌璺ㄦā鍧楅泦鎴愭祴璇?| 鏍稿績閾捐矾閫氳繃 | pass |
| 5 | 鎵ц `mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am test` | 绱㈠紩鍜屽埛鏂版祴璇曢€氳繃 | pass |
| 6 | 鎵ц `mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test` | 瑙ｆ瀽鍜?Trading 娴嬭瘯閫氳繃 | pass |
| 7 | 鎵ц `mvn -pl ai-agent-study-domain,ai-agent-study-app -am test` | 璺敱鍜岄泦鎴愬洖褰掗€氳繃 | pass |
| 8 | 鎵ц鍏ㄤ粨 `mvn test` | 缂栬瘧鍙婂叏浠撴祴璇曢€氳繃 | pass |

### 6.2 鎵嬪伐楠岃瘉姝ラ

| 姝ラ | 鎿嶄綔 | 棰勬湡缁撴灉 | status |
|------|------|------|------|
| 1 | 浣跨敤鐪熷疄 Tushare 鍚姩搴旂敤 | 鍚姩闃舵鍔犺浇绾?6,000 鏉″苟杩涘叆 `READY` | pass |
| 2 | 杈撳叆鈥滃垎鏋愬崕鍒涒€?| 杩斿洖绋冲畾缂栧彿鐨勫涓€欓€夛紝涓嶅垱寤?Trading run | pass |
| 3 | 鍒嗗埆鍥炲搴忓彿銆佸畬鏁村€欓€夊悕绉般€佸€欓€夊唴浠ｇ爜銆佸€欓€夊鍚嶇О鍜屽€欓€夊浠ｇ爜锛屽啀閫夋嫨蹇€熸垨瀹屾暣 | 鍓嶄笁绉嶉€変腑鍊欓€夛紱鍊欓€夊鍚嶇О鎴栧叚浣嶄唬鐮佸垏鎹㈢洰鏍囧苟淇濈暀妯″紡锛決UICK 涓嶅垱寤?run锛孎ULL 鍒涘缓鏂?run | pass |
| 4 | 妯℃嫙 Tushare 杩炵画鍒锋柊澶辫触 | 7 澶╁唴鏃х储寮曞彲鐢紝鍒版湡鍚庡悕绉拌姹傛敼璧拌繙绔簿纭煡璇㈠厹搴?| pass |
| 5 | 鍚屼竴 JVM 骞跺彂澶勭悊鍚屼竴 session | version CAS 鍜?Claim 淇濊瘉鍙湁涓€涓姹傛帴绠?| pass |
| 6 | 杈撳叆鍏綅浠ｇ爜骞惰皟鐢ㄧ洿鎺?Trading API | 涓ょ被鏄庣‘浠ｇ爜鍏ュ彛鍧囦笉鍙楀悕绉扮储寮曠姸鎬佸奖鍝?| pass |
| 7 | 鍚?session 鍜屼笉鍚?session 鍒嗗埆鎵撳紑涓や釜鏍囩椤靛苟浜ゅ弶杈撳叆 | 鍚?session 浠呮渶鏂?Pending 鐢熸晥锛涗笉鍚?session 鐩镐簰闅旂 | pass |
| 8 | 杈撳叆鈥滃畬鏁村垎鏋愪竴鍙偂绁ㄢ€濓紝鍐嶅洖澶嶆棤娉曡瘑鍒殑鍐呭 | 棣栬疆淇濈暀 FULL 骞跺彧闂偂绁紱鏃犳晥鍥炲閲嶅鑲＄エ婢勬竻涓斾笉鍒锋柊 TTL | pass |

---

## 7. 楠屾敹鏍囧噯

| 缂栧彿 | 楠屾敹椤?| 鏍囧噯 | status |
|------|------|------|------|
| AC-T01 | 姝ｅ父涓绘祦绋?| TC-001~TC-024 鍏ㄩ儴閫氳繃 | pass |
| AC-T02 | 寮傚父澶勭悊 | TC-101~TC-126 鍏ㄩ儴閫氳繃 | pass |
| AC-T03 | 杈圭晫琛屼负 | TC-201~TC-227 鍏ㄩ儴閫氳繃 | pass |
| AC-T04 | 鏍稿績鍥炲綊 | TC-301~TC-315 鍏ㄩ儴閫氳繃 | pass |
| AC-T05 | 鍚姩涓庡埛鏂?| 鍗曟鐑姞杞姐€佸け璐ヤ笉閲嶈瘯銆佹瘡鏃ュ埛鏂般€? 澶╄繃鏈熷拰鍘熷瓙鏇挎崲閫氳繃 | pass |
| AC-T06 | 浜屾婢勬竻 | 鍊欓€夐€夋嫨銆佸€欓€夊鍚嶇О/浠ｇ爜鍒囨崲銆乀TL銆佸悓 JVM 骞跺彂銆佸鏍囩椤佃竟鐣屽拰闈炴硶閫夋嫨閫氳繃 | pass |
| AC-T07 | 鎬ц兘 | 绾?6,000 鏉＄储寮曟煡璇?P95/P99 杈惧埌 Story 鎸囨爣 | pass |
| AC-T08 | 缂栬瘧涓庡叏浠撴祴璇?| `mvn test` 鎴愬姛锛屾棤鏃㈡湁娴嬭瘯鍥炲綊 | pass |

---

## 8. 椋庨櫓涓庤鏄?

| 椋庨櫓鐐?| 褰卞搷 | 搴斿鎺柦 |
|------|------|------|
| 鑷姩鍖栨祴璇曚笉璋冪敤鐪熷疄 Tushare | 鏃犳硶瑕嗙洊鐪熷疄鏉冮檺鍜屾暟鎹妯″彉鍖?| 淇濈暀鐪熷疄鐜鎵嬪伐鍔犺浇涓庢暟閲忔牳瀵?|
| Redis 娴嬭瘯鏇胯韩涓嶈兘瑕嗙洊鐪熷疄缃戠粶鏁呴殰 | Pending 璇诲啓浠嶆湁鐜椋庨櫓 | 鍦ㄦ祴璇曠幆澧冩墽琛?Redis 鏂繛鍜屾仮澶嶉獙璇?|
| 7 澶╁満鏅笉閫傚悎鐪熷疄绛夊緟 | 鏃堕棿娴嬭瘯鍙兘涓嶇ǔ瀹?| 娉ㄥ叆 `Clock`锛屼娇鐢ㄥ浐瀹氭椂闂存帹杩?|
| 鎬ц兘缁撴灉鍙?CI 鏈哄櫒褰卞搷 | 缁濆寤惰繜鍙兘娉㈠姩 | 鍏堥獙璇佺畻娉曞熀绾匡紝鎬ц兘闂ㄧ浣跨敤鍥哄畾鏁版嵁鍜岄鐑?|
| Story 1 灏氭湭瀹炴柦瀹屾垚 | Story 2 闆嗘垚娴嬭瘯绫诲彲鑳芥殏鏃舵棤娉曡惤鍦?| 鍏堝畬鎴?Story 1 鍓嶇疆濂戠害锛屽啀鎵ц璺?Story 鐢ㄤ緥 |

---

## 9. 鎵ц缁撴灉璁板綍

### 9.1 鎵ц缁撴灉

| 椤圭洰 | 缁撴灉 | status |
|------|------|------|
| 鍗曞厓娴嬭瘯 | 宸查€氳繃锛歋tory 2 鐩稿叧鍗曟祴涓庡绾︽祴璇曞叏閮ㄩ€氳繃 | pass |
| 闆嗘垚娴嬭瘯 | 宸查€氳繃锛歋tory 2 瀹氬悜闆嗘垚鍥炲綊涓庤法妯″潡闆嗘垚娴嬭瘯閫氳繃 | pass |
| 鎺ュ彛鍥炲綊 | 宸查€氳繃锛氳矾鐢便€丼SE銆乀rading API 涓庡吋瀹规€у洖褰掗€氳繃 | pass |
| 鎬ц兘娴嬭瘯 | 宸查€氳繃锛氱害 6,000 鏉＄储寮曟煡璇㈣揪鍒?Story 瀹氫箟鐨?P95/P99 鎸囨爣 | pass |
| 鎵嬪伐楠岃瘉 | 宸查€氳繃锛氱湡瀹?Tushare 闆嗘垚楠岃瘉閫氳繃锛屽悕绉版煡璇笌鍊欓€夐€夋嫨琛屼负绗﹀悎棰勬湡 | pass |
| 鍏ㄤ粨缂栬瘧娴嬭瘯 | 宸查€氳繃锛歚mvn test` 椤哄簭鎵ц `BUILD SUCCESS`锛?026-08-04 16:48:15 +08:00锛?| pass |

### 9.2 缁撹

- 是否达到提测或合并条件：是。
- 当前结论：Story 2 开发、回归与真实 Tushare 验证已完成；TC、AC 和执行项均已回填为 `pass`，可以提测或合并。
