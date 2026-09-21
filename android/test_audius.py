import urllib.request
import urllib.parse
import json

queries = ["EPILEPTIC TECHNO", "Toby Fox", "Despacito", "Skrillex"]
for q in queries:
    print(f"=== QUERY: {q} ===")
    url = f"https://api.audius.co/v1/tracks/search?query={urllib.parse.quote(q)}&limit=20&app_name=AuroraTest"
    print(f"URL: {url}")
    try:
        req = urllib.request.Request(url, headers={'Accept': 'application/json'})
        with urllib.request.urlopen(req) as response:
            print(f"HTTP: {response.status}")
            data = json.loads(response.read().decode('utf-8'))
            tracks = data.get('data', [])
            print(f"Raw results count: {len(tracks)}")
            
            valid_count = 0
            for i, t in enumerate(tracks):
                is_del = t.get('is_delete', False)
                is_avail = t.get('is_available', True)
                is_unlisted = t.get('is_unlisted', False)
                is_streamable = t.get('is_streamable', True)
                is_stream_gated = t.get('is_stream_gated', False)
                
                access = t.get('access', {})
                stream_access = access.get('stream', True)
                
                blocked = is_del or (not is_avail) or is_unlisted or (not is_streamable) or is_stream_gated or (not stream_access)
                
                if not blocked:
                    valid_count += 1
                
                if i < 3: # print first 3
                    artist = t.get('user', {}).get('name', '')
                    title = t.get('title', '')
                    print(f"  [{'BLOCKED' if blocked else 'OK'}] {artist} - {title}")
                    if blocked:
                        print(f"    Reasons: is_delete={is_del}, is_available={is_avail}, is_unlisted={is_unlisted}, is_streamable={is_streamable}, is_stream_gated={is_stream_gated}, stream_access={stream_access}")
            print(f"Filtered count (Valid): {valid_count}")
    except Exception as e:
        print(f"ERROR: {e}")
    print()
