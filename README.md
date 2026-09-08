# HARemote

Eenvoudige Android-app (Kotlin + Jetpack Compose) om je huis-sferen/TV via Home Assistant
te bedienen met 6 knoppen, plus een achtergrondservice die de GPS-locatie van het toestel
over een permanente (onbeveiligde) TCP-verbinding naar de HomeControl-server stuurt —
hetzelfde protocol als de oude HomeControl-app gebruikte. De achtergrondservice blijft op
dezelfde manier actief als GPSLogger (foreground service, wake lock, boot-restart en een
watchdog-alarm zodat Android de logging niet zomaar stilzet).

## Openen in Android Studio

1. Open Android Studio Ladybug (of nieuwer) → **Open** → kies deze map (`HARemote`).
2. Laat Gradle synchroniseren (de Gradle-wrapper zit al in het project).
3. Run op een fysiek toestel of emulator met Android 8.0 (API 26) of hoger.

`applicationId` is `com.koen.haremote`. Cleartext (plain `http://`) traffic is expliciet
toegestaan via `res/xml/network_security_config.xml`, anders blokkeert Android dit
sinds API 28 standaard — nodig omdat Home Assistant hier over het lokale LAN zonder
TLS aangesproken wordt.

## De 6 knoppen

Elke knop op het hoofdscherm doet één HTTP-call (GET of POST, in het lokale LAN, dus
gewoon `http://...`). Via het tandwiel-icoon rechtsboven kom je in **Instellingen**, waar
je per knop kan instellen:

- Naam en icoon
- REST URL
- HTTP-methode (GET/POST)
- JSON body (bij POST)
- Optioneel een bearer-token (`Authorization: Bearer ...`), handig als je rechtstreeks
  een Home Assistant `POST /api/services/<domain>/<service>` aanroept in plaats van een
  webhook.

De eenvoudigste opzet is een Home Assistant **webhook automation**: maak een automation
met trigger "Webhook", geef ze een id (bv. `film-modus`) en laat de knop posten naar
`http://<ha-ip>:8123/api/webhook/film-modus`. Dan is geen token nodig.

## Achtergrond GPS-logging

Onderaan **Instellingen** stel je de **hostnaam** en **poort** in van de HomeControl-server
(standaard poort `5678`, zelfde als de oude HomeControl-app). De schakelaar "Logging actief"
start/stopt de achtergrondservice.

De service houdt één permanente TCP-verbinding met de server open (`network/tcp/TcpLocationClient`),
in plaats van periodiek een REST-call te doen. Dat is exact het protocol van de oude
HomeControl-app (`"HCM"` + lengte + objectId + JSON-payload, zie `network/tcp/HcmProtocol.kt`):
na het verbinden stuurt de app haar toestelnaam, de server antwoordt met zijn naam, en
vanaf dan wordt elke locatie-fix als klein JSON-bericht doorgestuurd, met een keepalive
elke 30 seconden. Zolang de verbinding niet gelukt is (of wegvalt) probeert de app elke
15 seconden opnieuw te verbinden.

De server kan op elk moment het gewenste update-interval terugsturen (per toestel
ingesteld aan de serverkant). Een kort interval (≤ 20s) betekent dat de server frequente
én nauwkeurige locaties wil — de app schakelt dan naar de GPS-provider. Een langer
interval (of geen expliciet verzoek) mag minder nauwkeurig zijn, en gebruikt de
netwerk-provider (of, als die niet beschikbaar is, alsnog GPS) om batterij te sparen.

De verbinding is voorlopig **niet versleuteld** (bewust — encryptie volgt later); ze
verlaat sowieso nooit het lokale LAN.

### Waarom blijft dit actief op de achtergrond?

Net zoals GPSLogger combineert de app een aantal technieken zodat Android (en
fabrikant-specifiek batterijbeheer) de logging niet zomaar afsluit:

- Een **foreground service** met een permanente notificatie (verplicht sinds Android 8+
  om lang te blijven draaien).
- Een **partial wake lock** terwijl de service actief is.
- `START_STICKY`, zodat Android de service opnieuw opstart als het systeem het proces
  toch beëindigt om geheugen vrij te maken.
- Een **BOOT_COMPLETED-ontvanger** die logging na een herstart automatisch hervat.
- Een **watchdog-alarm** (`AlarmManager.setAndAllowWhileIdle`, elke 15 minuten) dat de
  service opnieuw start mocht die toch gestopt zijn.
- Een knop **"Batterijoptimalisatie negeren"** in Instellingen, die het systeemdialoog
  opent om HARemote uit te sluiten van Doze/batterijbeheer. Op toestellen met een eigen
  batterijbeheer (Samsung, Xiaomi/MIUI, Huawei, OnePlus, ...) moet je HARemote meestal
  ook daar apart als uitzondering toevoegen — dat kan de app zelf niet automatiseren,
  net zoals GPSLogger dat niet kan.

## Instellingen exporteren/importeren

**Instellingen → Exporteer JSON / Importeer JSON** schrijft/leest de volledige
configuratie (de 6 knoppen + de TCP-serverinstellingen) als één JSON-bestand, bijvoorbeeld:

```json
{
  "buttons": [
    { "id": 1, "label": "Film modus", "icon": "MOVIE", "url": "http://192.168.1.10:8123/api/webhook/film-modus", "method": "POST", "body": "{}", "bearerToken": "" },
    ...
  ],
  "tcpServerHost": "192.168.1.10",
  "tcpServerPort": 5678,
  "gpsLoggingEnabled": true
}
```

Zo kan je dezelfde configuratie makkelijk overzetten naar een ander toestel of
back-uppen.
