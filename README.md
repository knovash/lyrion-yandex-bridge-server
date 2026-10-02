# sa-ws-server (lyrion-yandex-bridge-server)

Облачный релей для [Lyrion Yandex Bridge](https://github.com/knovash/lyrion-yandex-bridge):
принимает HTTPS-запросы Яндекса (УДЯ-провайдер `/v1.0/...` и голосовой навык `/alice/`),
маршрутирует их **владельцу токена** по WebSocket на его java-клиент (плагин LMS) и возвращает
ответ Яндексу как есть. Также OAuth-брокер Яндекса/Spotify (client_secret не покидает сервер).

**Мультипользовательский режим**: `access_token` запроса → `login.yandex.ru/info` → числовой
yandexUid → клиент этого пользователя (`YandexUserResolver`). Fallback `anyUid` — только пока
подключён один клиент. `?uid=` в query — админ-оверрайд.

## Сборка и запуск
```
JAVA_HOME=~/.jdks/corretto-18.0.2 mvn package   # Java 17+, → target/cloud-server-1.0.jar
java -jar target/cloud-server-1.0.jar
```
Деплой — Docker (образ `eclipse-temurin:17-jre`, скрипт `mvn_package_app_docker_hub_build_push.sh`).

## Обязательные env-переменные (секреты в репозитории не хранятся!)
| Переменная | Назначение |
|---|---|
| `DOMAIN` | публичный адрес (OAuth redirect), например `https://alice-lms.zeabur.app` |
| `YANDEX_CLIENT_ID` / `YANDEX_CLIENT_SECRET` | OAuth-приложение Яндекса (oauth.yandex.ru) |
| `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` | приложение Spotify |
| `SKILL_ID` / `SKILL_DEV_TOKEN` | id и dev-токен навыка «Раз Два» (GET /info) |
| `TTS_API_KEY` | ключ Yandex Cloud TTS (GET /info) |

Redirect URI в консолях Яндекса/Spotify: `<DOMAIN>/callback` и `<DOMAIN>/spoti_callback`.
