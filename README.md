# busdash-edge

Edge-applicatie voor de Raspberry Pi in de bus. Stap 1: Victron BMV-712 uitlezen via VE.Direct.

## Starten in IntelliJ
1. *File → Open* → kies deze map (de `pom.xml` wordt automatisch herkend).
2. Draai de tests: rechtsklik op `src/test/java` → *Run 'All Tests'*.
3. Draai `App` met programma-argument `--fake` voor gesimuleerde accudata.

## Met echte hardware
- `--list-ports` toont de beschikbare seriële poorten.
- `--port /dev/ttyUSB0` (Pi/Linux) of `--port COM3` (Windows) leest de BMV uit.

## Naar de Pi
```bash
mvn package
scp target/busdash-edge-0.1.0-SNAPSHOT.jar pi@<ip>:~/
ssh pi@<ip> java -jar busdash-edge-0.1.0-SNAPSHOT.jar --port /dev/ttyUSB0
```
Op de Pi moet de gebruiker in de groep `dialout` zitten om de seriële poort te mogen openen:
`sudo usermod -aG dialout $USER` (daarna opnieuw inloggen).

Gebruik op de Pi bij voorkeur de vaste naam uit `/dev/serial/by-id/` in plaats van `/dev/ttyUSB0`:
dat nummer kan veranderen als de kabel opnieuw wordt ingestoken.

### Automatisch starten
In `deploy/busdash-edge.service` staat een systemd-service; installatie-instructies staan bovenin dat bestand.

### Gedrag bij storingen
- Is de poort bij het starten (nog) niet beschikbaar, of valt de kabel weg, dan blijft de app
  opnieuw verbinden (wachttijd oplopend van 2 tot 30 seconden).
- Is de laatste geldige meting ouder dan 10 seconden, dan geeft de monitor geen meting meer terug
  in plaats van een verouderde waarde.

## Structuur
- `vedirect/` – protocol: streaming parser met checksum-validatie, seriële bron
- `battery/` – domein: `BatteryMonitor`-interface met een echte (BMV) en een fake implementatie

## Volgende stappen
MQTT-publish → Spring Boot-backend → dashboard → schakelen.
