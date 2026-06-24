import http.client, ssl, json, os

FILES = {
    "model.json":  "https://huggingface.co/csukuangfj/vits-piper-zh_CN-huayan-x_low/resolve/main/zh_CN-huayan-x_low.onnx.json?download=true",
    "tokens.txt":  "https://huggingface.co/csukuangfj/vits-piper-zh_CN-huayan-x_low/resolve/main/tokens.txt?download=true",
    "lexicon.txt": "https://huggingface.co/csukuangfj/vits-piper-zh_CN-huayan-x_low/resolve/main/lexicon.txt?download=true",
}

BASE = r"F:\agent项目\LightAgent\app\src\main\assets\vits"
ctx = ssl._create_unverified_context()

def download_file(url):
    host = "huggingface.co"
    path = url
    if path.startswith("https://"):
        path = path[8:]
        slash = path.find("/")
        host = path[:slash]
        path = path[slash:]
    
    for _ in range(20):
        try:
            conn = http.client.HTTPSConnection(host, context=ctx, timeout=30)
            conn.request("GET", path)
            resp = conn.getresponse()
            body = resp.read()
            conn.close()
            
            if resp.status in (301, 302, 303, 307, 308):
                loc = resp.getheader("Location") or resp.getheader("location")
                if not loc: return body
                
                # If redirect to API
                if loc.startswith("/api/resolve-cache/"):
                    conn2 = http.client.HTTPSConnection(host, context=ctx, timeout=30)
                    conn2.request("GET", loc)
                    resp2 = conn2.getresponse()
                    api_body = resp2.read()
                    conn2.close()
                    
                    # Check if API returns a download URL
                    try:
                        api_json = json.loads(api_body.decode())
                        if "url" in api_json:
                            loc = api_json["url"]
                        elif "downloadUrl" in api_json:
                            loc = api_json["downloadUrl"]
                        else:
                            # The API response IS the file content
                            return api_body
                    except:
                        # Not JSON, the response IS the file
                        return api_body
                
                # Follow redirect
                if loc.startswith("https://"):
                    loc = loc[8:]
                if "/" in loc:
                    slash = loc.find("/")
                    host = loc[:slash]
                    path = loc[slash:]
                elif not loc.startswith("/"):
                    path = "/" + loc
                else:
                    path = loc
            else:
                return body
        except Exception as e:
            print(f"  retry after error: {e}")
    
    return b""

for fname, url in FILES.items():
    data = download_file(url)
    out = os.path.join(BASE, fname)
    with open(out, "wb") as f:
        f.write(data)
    print(f"OK {fname}: {len(data)} bytes")
