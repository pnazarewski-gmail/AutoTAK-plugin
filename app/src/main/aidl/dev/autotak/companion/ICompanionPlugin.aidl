// Interfejs wystawiany przez wtyczkę; woła go aplikacja AutoTAK.
package dev.autotak.companion;

interface ICompanionPlugin {
    /** Prośba o ponowne przesłanie pełnego obrazu. */
    oneway void requestSnapshot();

    /** Zaznaczenie obiektu w ATAK; null zdejmuje zaznaczenie. */
    oneway void select(String uid);

    /** Zestaw ikon jako paczka zip (iconset.xml i pliki ikon) — do odczytu strumieniem. */
    ParcelFileDescriptor openIconSet(String uid);
}
