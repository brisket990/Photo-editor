# Retoucheur EXIF

Page web pour modifier la **date de prise de vue** et la **position GPS** d'une photo JPEG.

- Tout se passe dans le navigateur : aucune photo n'est envoyée sur un serveur.
- L'image n'est pas recompressée (pixels identiques) et l'Ultra HDR (MPF + carte de gain) est conservé.
- Champs écrits : `DateTime`, `DateTimeOriginal`, `DateTimeDigitized`, `OffsetTime*`, et le bloc GPS (latitude, longitude, altitude, horodatage UTC).
- La position peut aussi être supprimée ; les anciennes valeurs sont effacées du fichier.

Utilisation : ouvrir la page publiée sur GitHub Pages, choisir une photo, modifier, enregistrer. Le fichier modifié est une copie ; l'original n'est pas touché.
