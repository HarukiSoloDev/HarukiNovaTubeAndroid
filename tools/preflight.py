from pathlib import Path
import re, sys, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / 'app/src/main/java/com/harukisolodev/harukistream'
GRADLE = (ROOT/'app/build.gradle.kts').read_text(encoding='utf-8')
VERSION = (ROOT/'VERSION.txt').read_text(encoding='utf-8').strip()
errors=[]
def fail(msg): errors.append(msg)
def need(text, token, where):
    if token not in text: fail(f'{where}: missing {token}')

def text(rel): return (PKG/rel).read_text(encoding='utf-8', errors='ignore')

# Identity / dependencies
need(GRADLE, 'versionName = "0.8.1"', 'Gradle')
need(GRADLE, 'versionCode = 810', 'Gradle')
need(GRADLE, 'applicationId = "com.harukisolodev.harukistream"', 'Gradle')
for dep in ['media3-exoplayer:1.11.0','media3-session:1.11.0','media3-datasource-okhttp:1.11.0','NewPipeExtractor:v0.26.5','TAndroidLame:1.1']:
    need(GRADLE, dep, 'Gradle')
need(GRADLE, 'exclude(group = "com.android.support")', 'TAndroidLame AndroidX exclusion')
if VERSION != '0.8.1': fail(f'VERSION.txt is {VERSION}, expected 0.8.1')
strings=(ROOT/'app/src/main/res/values/strings.xml').read_text(encoding='utf-8')
need(strings, 'Haruki NovaTube', 'strings.xml')
need(strings, '<string name="launcher_name">NovaTube</string>', 'launcher label')

# Required source files
required=[
 'ui/AdaptiveLayout.kt','data/LocalPlaylistStore.kt','ui/screens/LocalPlaylistsScreen.kt','download/Mp3Transcoder.kt',
 'data/SavedVideoStore.kt','data/RecommendationStore.kt','data/NovaAiModels.kt','data/NovaAiSearchEngine.kt',
 'ui/screens/NovaAiScreen.kt','ui/screens/LibraryHubScreen.kt','ui/screens/YouHubScreen.kt','ui/screens/SettingsScreen.kt',
 'ui/screens/ShortsScreen.kt','ui/screens/VerticalNativePlayer.kt','ui/screens/WatchScreen.kt','ui/screens/YouTubeScreen.kt',
 'ui/BrowseViewModel.kt','ui/HarukiViewModel.kt','ui/HarukiApp.kt','extractor/BrowseRepository.kt','extractor/HarukiExtractor.kt',
 'player/PlaybackService.kt','player/PlaybackDataSourceFactory.kt','player/PlaybackSelectionStore.kt','download/DownloadWorker.kt',
 'download/DownloadRequestStore.kt','download/DownloadActionReceiver.kt','download/DownloadLaunch.kt'
]
for rel in required:
    if not (PKG/rel).exists(): fail('Missing '+rel)

# XML
for p in (ROOT/'app/src/main').rglob('*.xml'):
    try: ET.parse(p)
    except Exception as e: fail(f'Malformed XML {p.relative_to(ROOT)}: {e}')

# Kotlin delimiter guard
all_kt=list(PKG.rglob('*.kt'))
def strip(src):
    src=re.sub(r'""".*?"""','""',src,flags=re.S)
    src=re.sub(r'"(?:\\.|[^"\\])*"','""',src)
    src=re.sub(r"'(?:\\.|[^'\\])'","''",src)
    src=re.sub(r'/\*.*?\*/','',src,flags=re.S)
    src=re.sub(r'//.*','',src)
    return src
pairs={'(':')','[':']','{':'}'}; close={v:k for k,v in pairs.items()}
for p in all_kt:
    stack=[]
    for ch in strip(p.read_text(encoding='utf-8',errors='ignore')):
        if ch in pairs: stack.append(ch)
        elif ch in close:
            if not stack or stack[-1] != close[ch]:
                fail(f'Unbalanced {ch}: {p.relative_to(ROOT)}'); break
            stack.pop()
    if stack: fail(f'Unclosed delimiter {p.relative_to(ROOT)}: {stack[-6:]}')

all_text='\n'.join(p.read_text(encoding='utf-8',errors='ignore') for p in all_kt)
for token in ['android.webkit.WebView','com.google.android.gms.ads','MobileAds.initialize','admob','TikTokExtractor']:
    if token.lower() in all_text.lower(): fail('Removed/forbidden marker remains: '+token)
for pat,label in [
    (r'sk_live_[A-Za-z0-9]+','Stripe secret'),
    (r'AIza[0-9A-Za-z_-]{20,}','Google API key'),
    (r'client_secret\s*[=:]\s*["\'][^"\']+','OAuth secret')
]:
    if re.search(pat, all_text, re.I): fail('Possible embedded '+label)

# Core sources
app=text('ui/HarukiApp.kt'); adaptive=text('ui/AdaptiveLayout.kt'); yt=text('ui/screens/YouTubeScreen.kt')
shorts=text('ui/screens/ShortsScreen.kt'); watch=text('ui/screens/WatchScreen.kt'); vm=text('ui/BrowseViewModel.kt')
reco=text('data/RecommendationStore.kt'); playlists=text('data/LocalPlaylistStore.kt'); ai=text('data/NovaAiSearchEngine.kt')
worker=text('download/DownloadWorker.kt'); mp3=text('download/Mp3Transcoder.kt'); req=text('download/DownloadRequestStore.kt')
playback=text('player/PlaybackService.kt'); cache=text('player/PlaybackDataSourceFactory.kt'); settings=text('ui/screens/SettingsScreen.kt')
extractor=text('extractor/HarukiExtractor.kt'); hvm=text('ui/HarukiViewModel.kt')

# Adaptive UI / navigation
for token in ['NovaWindowClass','CAR_LANDSCAPE','useNavigationRail','feedColumns','largeTouchTargets']:
    need(adaptive, token, 'Adaptive layout')
for token in ['BoxWithConstraints','novaAdaptiveInfo','NovaNavigationRail','NovaBottomBar','adaptive = adaptive']:
    need(app, token, 'Adaptive app shell')
for token in ['leaveShorts(restoreWatch = true)','PlaybackService.pauseForShorts()','watchWasMinimizedBeforeShorts']:
    need(app, token, 'Shorts playback preservation')
for token in ['adaptive.feedColumns','downloadedKeys','Downloaded']:
    need(yt, token, 'Responsive/download-aware feed')
for token in ['adaptive: NovaAdaptiveInfo','widthIn(max = if (adaptive.largeTouchTargets) 430.dp else 540.dp)']:
    need(shorts, token, 'Responsive Shorts')
for token in ['playerWidthModifier','widthIn(max = if (adaptive.largeTouchTargets) 840.dp else 1040.dp)']:
    need(watch, token, 'Responsive Watch player')

# Offline reuse
for token in ['offlineVideoByKey','downloadedKeys','browseVm.openOffline(item, it)']:
    need(app, token, 'Offline selection routing')
for token in ['fun openOffline','serviceName = "Downloaded"','Offline • no streaming','libraryItem.uri']:
    need(vm, token, 'Offline playback')

# Local playlists
for token in ['data class LocalPlaylist','fun create','fun addVideo','fun removeVideo','fun delete']:
    need(playlists, token, 'Local playlist store')
for token in ['createLocalPlaylist','addToLocalPlaylist','prepareLocalPlaylistPlayback','recordPlaylistSave']:
    need(vm, token, 'Local playlist ViewModel')
for token in ['PlaylistPickerSheet','onCreatePlaylist','onAddToPlaylist']:
    need(watch, token, 'Watch playlist UI')
need(app, 'Destination.PLAYLISTS', 'Library playlist route')

# MP3
for token in ['AndroidLame','LameBuilder','setOutBitrate(bitrateKbps)','encodeBufferInterLeaved','flush(mp3Buffer)']:
    need(mp3, token, 'MP3 transcoder')
for token in ['transcodeToMp3','sourceExtension']:
    need(req, token, 'MP3 request persistence')
for token in ['fun queueMp3','MP3 • 192 kbps','transcodeToMp3 = true']:
    need(hvm, token, 'MP3 queue')
for token in ['Mp3Transcoder.transcode','Converting to MP3']:
    need(worker, token, 'MP3 worker')
for token in ['MP3 audio','Download MP3']:
    need(watch, token, 'MP3 download UI')

# Recommendation quality
for token in ['recordWatchMetadata','recordSaveMetadata','recordDownloadMetadata','recordPlaylistSave','extractHashtags','looksKidFocused','qualityPenalty','diversifyLongForm','topicSignature']:
    need(reco, token, 'Recommendation profile')
need(reco, 'SavedVideoStore.canonicalKey(it.first.url, it.first.id)', 'Canonical feed dedupe')
need(reco, 'out.size < 24', 'Early feed channel cap')
for banned in ['viral shorts','funny shorts','popular videos']:
    if banned in reco.lower(): fail('Broad low-quality discovery query remains: '+banned)

# Nova AI
for token in ['LOCAL_PLAYLIST','NovaAiSearchMode.FAST','NovaAiSearchMode.SMART','NovaAiSearchMode.DEEP','SearchProfile','profile.maxQueries','profile.enrichTop','stableKey(video)']:
    need(ai, token, 'Nova AI v0.8.1')
need(vm, 'NovaAiMatchSource.LOCAL_PLAYLIST', 'Nova AI playlist evidence')

# Playback / 720p+
for token in ['setBufferDurationsMsForStreaming(45_000, 120_000, 2_500, 6_000)','if (preferredHeight < 720)','bufferedAhead >= 55_000L','ahead < 35_000L','pauseForShorts','resumeAfterShorts']:
    need(playback, token, 'HD playback protection')
for token in ['OkHttpDataSource.Factory','Protocol.HTTP_2','Accept-Encoding','identity']:
    need(cache, token, 'HTTP/2 playback data source')
for token in ['getResolution()','isVideoOnly()','bitrate','fps']:
    need(extractor, token, 'Stream quality metadata')
if '.resolution' in extractor or '.isVideoOnly' in extractor.replace('.isVideoOnly()', ''):
    fail('Deprecated NewPipe fields are still used directly')

# Prior critical regressions
if 'Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)' in settings[settings.find('private fun SettingsCard'):]:
    fail('SettingsCard nested vertical scroll regression returned')
if watch.count('}.collect { (last, total) ->') != 1:
    fail('Watch related-feed observer should contain exactly one collect block')
if vm.count('if (queueItems.isNotEmpty() && queuePosition == queueItems.lastIndex) loadMoreRelated()') != 1:
    fail('Playlist-end loadMoreRelated trigger should appear exactly once')
need(playback, '.setSlots(CommandButton.SLOT_FORWARD)', 'User Android Studio notification slot fix')
need(text('player/PlaybackSelectionStore.kt'), 'qualityByMediaId', 'Per-video quality persistence')
for token in ['downloadChunkedRanges','HTTP_CHUNK_BYTES = 8L * 1024L * 1024L','Range", "bytes=0-0','ByteArray(512 * 1024)','registerDownloadLane']:
    need(worker, token, 'v0.8.1 smart chunk downloads')
if '.setSubText("Haruki NovaTube")' in worker:
    fail('Download notification repeats app name')

if errors:
    print('PRECHECK FAILED')
    for e in errors: print(' -', e)
    sys.exit(1)
print('PRECHECK OK — Haruki NovaTube Android v0.8.1')
print(f'Checked {len(all_kt)} Kotlin files: adaptive UI, offline reuse, MP3, playlists, recommendations, Nova AI modes, playback/Shorts restore, smart downloads, prior critical regressions, XML, secrets/ads, and delimiter balance.')
