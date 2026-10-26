# Retoucheur EXIF

Page web pour modifier la **date de prise de vue** et la **position GPS** d'une photo JPEG.

- Tout se passe dans le navigateur : aucune photo n'est envoyée sur un serveur.
- L'image n'est pas recompressée (pixels identiques) et l'Ultra HDR (MPF + carte de gain) est conservé.
- Champs écrits : `DateTime`, `DateTimeOriginal`, `DateTimeDigitized`, `OffsetTime*`, et le bloc GPS (latitude, longitude, altitude, horodatage UTC).
- La position peut aussi être supprimée ; les anciennes valeurs sont effacées du fichier.

Utilisation : ouvrir la page publiée sur GitHub Pages, choisir une photo, modifier, enregistrer. Le fichier modifié est une copie ; l'original n'est pas touché.

## Appli Android

Le dossier `android/` contient une petite appli qui affiche la même page, avec deux avantages sur le navigateur :

- elle lit la photo d'origine avec sa **vraie position GPS** (autorisation « position des médias »), alors qu'Android la remplace par 0, 0 pour les navigateurs ;
- elle enregistre directement dans la galerie, en copie ou en remplaçant la photo d'origine (après accord).

L'APK est compilé par GitHub Actions à chaque modification. Dernière version : https://github.com/brisket990/Photo-editor/releases/latest/download/RetoucheurEXIF.apk

La clé de signature n'est pas dans le dépôt : elle est stockée dans les secrets GitHub Actions `KEYSTORE_BASE64` et `KEYSTORE_PASSWORD`. Sans ces secrets, la compilation s'arrête.
