import requests
import json
import base64

BASE_URL = "http://localhost:8080/tallac"

def get_request(url):
    http_headers = {'Accept' : 'application/json'}

    url = BASE_URL + url
    print("url == " + url)
    resp = requests.get(url, headers=http_headers)
    if resp.status_code != 200:
        raise RestException("Rest Request Failed", url, resp.status_code,
                            resp.content)
    print(resp.content)
    # d = base64.b64encode(resp.content).decode()
    d = str(resp.content, encoding = "utf-8")
    # return(json.loads(d))
    print(json.loads(d))

def get_stats():
    url = "/api/blacklist/stats"
    return get_request(url)

get_stats()
print("ok")

# # bytes object
#   b = b"example"
 
#   # str object
#   s = "example"
 
#   # str to bytes
#   bytes(s, encoding = "utf8")
 
#   # bytes to str
#   str(b, encoding = "utf-8")
 
#   # an alternative method
#   # str to bytes
#   str.encode(s)
 
#   # bytes to str
#   bytes.decode(b)