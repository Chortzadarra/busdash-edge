# busdash-edge

Edge-applicatie voor de Raspberry Pi in de bus. Leest de Victron BMV-712 (accu) en SmartSolar MPPT (zon)
uit via VE.Direct, stuurt het Waveshare-relaisbord aan en toont alles op één webdashboard met login.

## Starten in IntelliJ
1. *File → Open* → kies deze map (de `pom.xml` wordt automatisch herkend).
2. Draai de tests: rechtsklik op `src/test/java` → *Run 'All Tests'*.
3. Draai `App` met programma-argumenten `--dashboard --fake --no-login` en open `http://localhost:8080/`.
   Alles is dan gesimuleerd; een "dag" duurt 3 minuten, zodat je de bus ziet slapen, wakker worden
   en zijn zonnebril opzetten.

## Dashboard
Eén pagina, geschikt voor telefoon en computer:

1. **De bus** met een stemming:
   - **zonnebril op** als de panelen flink leveren (vanaf 100 W, aan te passen met `--sunny-watts`);
   - **slapen** als het nacht is (0 W en bijna geen paneelspanning);
   - **tevreden en wakker** als er niet zoveel gebeurt.
   Er zit wat speling in (hysterese), zodat hij bij een wolkje niet steeds wisselt.
2. **Accu (BMV-712)**: laadpercentage, spanning, stroom, vermogen, resterende tijd, verbruikte Ah,
   en temperatuur of startaccu als de aux-ingang daarvoor is ingesteld.
3. **Zon (SmartSolar MPPT)**: paneelvermogen en -spanning, laadstroom, laadstatus, opbrengst en
   hoogste vermogen vandaag.
4. **Relais**: vaste nummers 1 t/m 8 (= CH1..CH8), eigen naam via het potloodje. Standaard is alleen
   relais 1 zichtbaar; met **+** voeg je er een toe, tot maximaal 8. Via het potloodje kun je een relais ook
   weer verwijderen (het gaat dan uit).

```bash
java -jar busdash-edge-0.1.0-SNAPSHOT.jar --dashboard \
  --bmv-port /dev/serial/by-id/usb-VictronEnergy_BV_VE_Direct_cable_VE111111-if00-port0 \
  --mppt-port /dev/serial/by-id/usb-VictronEnergy_BV_VE_Direct_cable_VE222222-if00-port0
```
Laat je `--bmv-port` of `--mppt-port` weg, dan staat dat blok op "Niet aangesloten".
Andere webpoort: `--http-port 8081`. Instellingen staan in `~/.busdash` (aan te passen met `--data-dir`).

### Login
Het dashboard start alleen als er een gebruiker is. Aanmaken (of wachtwoord wijzigen):
```bash
java -jar busdash-edge-0.1.0-SNAPSHOT.jar --set-password jeroen
```
- Wachtwoorden worden alleen als PBKDF2-hash opgeslagen in `~/.busdash/users.properties`.
- Na inloggen blijf je 30 dagen ingelogd (cookie, HttpOnly en SameSite=Strict). Na een herstart van de
  app moet je opnieuw inloggen.
- Na 10 foute pogingen binnen 15 minuten wordt dat adres 5 minuten geblokkeerd.
- Komt het verzoek via HTTPS binnen achter een reverse proxy (`X-Forwarded-Proto: https`), dan krijgt de
  cookie ook de vlag `Secure`.
- `--no-login` is alleen bedoeld voor ontwikkelen op je laptop.

**Nog niet klaar voor internet.** Hosten op een eigen domein volgt later: dat moet via HTTPS (bijvoorbeeld
een reverse proxy of tunnel). Zet poort 8080 niet zomaar open in je router.

## Met echte hardware
- `--list-ports` toont de beschikbare seriële poorten.
- `--port /dev/ttyUSB0` (Pi/Linux) of `--port COM3` (Windows) leest alleen de BMV uit naar de console.

Gebruik op de Pi bij voorkeur de vaste namen uit `/dev/serial/by-id/` in plaats van `/dev/ttyUSB0`:
met twee VE.Direct-kabels (BMV en MPPT) kan de nummering anders wisselen.

### Naar de Pi
```bash
mvn package
scp target/busdash-edge-0.1.0-SNAPSHOT.jar pi@<ip>:~/
```
De gebruiker moet in de groepen `dialout` (seriële poorten) en `gpio` (relais) zitten:
`sudo usermod -aG dialout,gpio $USER` (daarna opnieuw inloggen).

### Automatisch starten
In `deploy/busdash-edge.service` staat een systemd-service; installatie-instructies staan bovenin dat bestand.

### Gedrag bij storingen
- Is een poort bij het starten (nog) niet beschikbaar, of valt een kabel weg, dan blijft de app
  opnieuw verbinden (wachttijd oplopend van 2 tot 30 seconden).
- Is de laatste geldige meting ouder dan 10 seconden, dan toont het dashboard "Geen actuele gegevens"
  in plaats van een verouderde waarde.

## Relaisbord (Waveshare RPi Relay Board (B), 8 kanalen)
Aansturing via GPIO met Pi4J en libgpiod (werkt ook op de Pi 5).

- `--relay-test` zet CH1 t/m CH8 één voor één een seconde aan. **Doe dit eerst zonder iets aangesloten**
  en controleer dat het juiste relais klikt (LED op het bord), want de pinindeling
  (BCM 5, 6, 13, 16, 19, 20, 21, 26) komt uit de Waveshare-documentatie.
- `--relay-console` om met de hand te schakelen: `3 aan`, `3 uit`, `alles uit`, `status`, `stop`.
- Voeg `--fake` toe om zonder Pi te testen, bijv. `--relay-console --fake`.

Het bord is low-active (pin laag = relais aan); dat zit verstopt in `WaveshareRelayBoard`.
Bij afsluiten of Ctrl+C gaan alle relais uit. Of een relais aan stond wordt niet bewaard.

Klikken er relais aan tijdens het opstarten van de Pi, voordat de app draait? Zet de pinnen dan
al vroeg hoog door deze regel toe te voegen aan `/boot/firmware/config.txt`:
```
gpio=5,6,13,16,19,20,21,26=op,dh
```

Let op: de contacten kunnen maximaal 30 V DC / 5 A (ohmse last) schakelen. Laat zwaardere of
inductieve 12V-verbruikers (koelkast, omvormer, pomp) via een zwaarder relais of contactor lopen.

## Structuur
- `vedirect/` – protocol: streaming parser met checksum-validatie, seriële bron
- `battery/` – BMV-712: `BatteryMonitor` met een echte en een fake implementatie
- `solar/` – SmartSolar MPPT: `SolarCharger` met een echte en een fake implementatie (versnelde dag)
- `status/` – stemming van de bus (`MoodTracker`)
- `relay/` – `RelayBoard` met het echte Waveshare-bord en een fake, namen/zichtbaarheid, testconsole
- `web/` – webserver (zit in de JDK), login (`Auth`, `UserStore`) en JSON-API; de pagina's staan in
  `src/main/resources/dashboard/`

## Volgende stappen
Hosten op een eigen domein (HTTPS) → eventueel MQTT/backend voor geschiedenis en grafieken.
