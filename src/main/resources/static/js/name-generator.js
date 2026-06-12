const NameGenerator = (() => {
    const adjectives = [
        'Hızlı', 'Mavi', 'Kırmızı', 'Yeşil', 'Mor', 'Turuncu', 'Sarı', 'Gümüş', 'Altın', 'Siyah',
        'Güçlü', 'Parlak', 'Sessiz', 'Cesur', 'Akıllı', 'Neşeli', 'Meraklı', 'Sakin', 'Özgür', 'Hevesli',
        'Derin', 'Yüksek', 'Gizli', 'Büyük', 'Küçük', 'Eski', 'Yeni', 'Sıcak', 'Soğuk', 'Işıltılı',
        'Keskin', 'Süratli', 'Kocaman', 'Korkusuz', 'Gizemli', 'Kahraman', 'Asil', 'Fırtınalı', 'Güzel', 'Yıldırım'
    ];

    const nouns = [
        'Kaplan', 'Kartal', 'Aslan', 'Kurt', 'Panter', 'Şahin', 'Ejderha', 'Tilki', 'Ayı', 'Balina',
        'Bulut', 'Yıldız', 'Güneş', 'Ay', 'Fırtına', 'Şimşek', 'Rüzgar', 'Ateş', 'Deniz', 'Dağ',
        'Elmas', 'Demir', 'Çelik', 'Mercan', 'Kristal', 'Granit', 'Volkan', 'Akik', 'Obsidyen', 'Kaya',
        'Robot', 'Roket', 'Uçak', 'Gemi', 'Kalkan', 'Kılıç', 'Kule', 'Köprü', 'Kapı', 'Anahtar'
    ];

    const cache = new Map();

    function hash(str) {
        let h = 5381;
        for (let i = 0; i < str.length; i++) {
            h = (((h << 5) + h) ^ str.charCodeAt(i)) >>> 0;
        }
        return h;
    }

    function getDisplayName(deviceId) {
        if (cache.has(deviceId)) return cache.get(deviceId);
        const h = hash(deviceId);
        const adj = adjectives[h % adjectives.length];
        const noun = nouns[Math.floor(h / adjectives.length) % nouns.length];
        const suffix = deviceId.slice(-3);
        const name = `${adj} ${noun} ${suffix}`;
        cache.set(deviceId, name);
        return name;
    }

    return { getDisplayName };
})();
