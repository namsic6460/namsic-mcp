//! Per-point pixel colors and rect-average color from a screenshot
//! (used by `android_sample_pixels`, device pixels).

use std::fmt::Write as _;

use image::RgbaImage;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Point {
    pub x: i32,
    pub y: i32,
}

/// Java `String.split(String)` for a single literal separator: trailing empty
/// pieces are dropped, and an input without any separator yields itself.
pub fn java_split(s: &str, sep: char) -> Vec<&str> {
    if !s.contains(sep) {
        return vec![s];
    }
    let mut parts: Vec<&str> = s.split(sep).collect();
    while parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    parts
}

/// Java `String.trim()`: strips leading/trailing chars `<= ' '`.
pub fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// Java `Integer.parseInt` (ASCII digits, optional sign).
pub fn java_parse_int(s: &str) -> Option<i32> {
    let digits = s.strip_prefix(['+', '-']).unwrap_or(s);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    s.parse().ok()
}

fn is_blank(s: Option<&str>) -> bool {
    s.is_none_or(|v| v.trim().is_empty())
}

/// Parses `"x1,y1;x2,y2"`. Blank input yields an empty list.
pub fn parse_points(points: Option<&str>) -> Result<Vec<Point>, String> {
    let mut result = Vec::new();
    let Some(points) = points.filter(|_| !is_blank(points)) else {
        return Ok(result);
    };
    for token in java_split(points, ';') {
        let trimmed = java_trim(token);
        if trimmed.is_empty() {
            continue;
        }
        let xy = java_split(trimmed, ',');
        if xy.len() != 2 {
            return Err(format!("invalid point '{trimmed}' (expected 'x,y')"));
        }
        match (
            java_parse_int(java_trim(xy[0])),
            java_parse_int(java_trim(xy[1])),
        ) {
            (Some(x), Some(y)) => result.push(Point { x, y }),
            _ => {
                return Err(format!("invalid point '{trimmed}' (expected integer x,y)"));
            }
        }
    }
    Ok(result)
}

/// Parses `"x,y,w,h"`. Blank input yields `None`; w/h must be positive.
pub fn parse_rect(rect: Option<&str>) -> Result<Option<[i32; 4]>, String> {
    let Some(rect) = rect.filter(|_| !is_blank(rect)) else {
        return Ok(None);
    };
    let parts = java_split(rect, ',');
    if parts.len() != 4 {
        return Err(format!("invalid rect '{rect}' (expected 'x,y,w,h')"));
    }
    let mut vals = [0i32; 4];
    for (i, part) in parts.iter().enumerate() {
        vals[i] = java_parse_int(java_trim(part))
            .ok_or_else(|| format!("invalid rect '{rect}' (expected integers)"))?;
    }
    if vals[2] <= 0 || vals[3] <= 0 {
        return Err(format!("rect w/h must be positive: {rect}"));
    }
    Ok(Some(vals))
}

/// JSON with each point's `#RRGGBBAA` and (if given) the rect's average `#RRGGBB`.
pub fn sample_to_json(
    image: &RgbaImage,
    points: &[Point],
    rect: Option<[i32; 4]>,
) -> Result<String, String> {
    let width = i64::from(image.width());
    let height = i64::from(image.height());
    let mut sb = String::new();
    let _ = write!(
        sb,
        "{{\"imageWidth\":{width},\"imageHeight\":{height},\"points\":["
    );
    for (i, p) in points.iter().enumerate() {
        let (px, py) = (i64::from(p.x), i64::from(p.y));
        if px < 0 || px >= width || py < 0 || py >= height {
            return Err(format!(
                "point ({},{}) is outside the image {width}x{height}",
                p.x, p.y
            ));
        }
        if i > 0 {
            sb.push(',');
        }
        let [r, g, b, a] = image.get_pixel(p.x as u32, p.y as u32).0;
        let _ = write!(
            sb,
            "{{\"x\":{},\"y\":{},\"rgbaHex\":\"#{r:02X}{g:02X}{b:02X}{a:02X}\"}}",
            p.x, p.y
        );
    }
    sb.push(']');
    if let Some(rect) = rect {
        let [rx, ry, rw, rh] = rect.map(i64::from);
        let x0 = rx.max(0);
        let y0 = ry.max(0);
        let x1 = width.min(rx + rw);
        let y1 = height.min(ry + rh);
        if x0 >= x1 || y0 >= y1 {
            return Err(format!("rect is outside the image {width}x{height}"));
        }
        let (mut r, mut g, mut b) = (0i64, 0i64, 0i64);
        for y in y0..y1 {
            for x in x0..x1 {
                let px = image.get_pixel(x as u32, y as u32).0;
                r += i64::from(px[0]);
                g += i64::from(px[1]);
                b += i64::from(px[2]);
            }
        }
        let count = (x1 - x0) * (y1 - y0);
        let _ = write!(
            sb,
            ",\"rectAverage\":{{\"x\":{x0},\"y\":{y0},\"w\":{},\"h\":{},\"rgbHex\":\"#{:02X}{:02X}{:02X}\"}}",
            x1 - x0,
            y1 - y0,
            r / count,
            g / count,
            b / count
        );
    }
    sb.push('}');
    Ok(sb)
}

#[cfg(test)]
mod tests {
    use image::Rgba;

    use super::*;

    #[test]
    fn parse_points_parses_semicolon_separated_list() {
        let points = parse_points(Some(" 10,20 ; 30,40 ;")).unwrap();
        assert_eq!(points, vec![Point { x: 10, y: 20 }, Point { x: 30, y: 40 }]);
        assert!(parse_points(None).unwrap().is_empty());
        assert!(parse_points(Some("  ")).unwrap().is_empty());
    }

    #[test]
    fn parse_points_rejects_malformed_input() {
        assert!(parse_points(Some("10")).is_err());
        assert!(parse_points(Some("10,20,30")).is_err());
        assert!(parse_points(Some("a,b")).is_err());
        assert_eq!(
            parse_points(Some("10")).unwrap_err(),
            "invalid point '10' (expected 'x,y')"
        );
        assert_eq!(
            parse_points(Some("a,b")).unwrap_err(),
            "invalid point 'a,b' (expected integer x,y)"
        );
    }

    #[test]
    fn parse_points_follows_java_split_trailing_empty_semantics() {
        // Java "10,20,".split(",") drops the trailing empty piece -> 2 parts.
        assert_eq!(
            parse_points(Some("10,20,")).unwrap(),
            vec![Point { x: 10, y: 20 }]
        );
        // A leading empty piece is kept -> 3 parts -> error.
        assert!(parse_points(Some(",10,20")).is_err());
    }

    #[test]
    fn parse_rect_parses_and_validates() {
        assert_eq!(parse_rect(None).unwrap(), None);
        assert_eq!(parse_rect(Some(" ")).unwrap(), None);
        assert_eq!(parse_rect(Some("1, 2, 3, 4")).unwrap(), Some([1, 2, 3, 4]));
        assert!(parse_rect(Some("1,2,3")).is_err());
        assert!(parse_rect(Some("1,2,0,4")).is_err());
        assert!(parse_rect(Some("a,b,c,d")).is_err());
        assert_eq!(
            parse_rect(Some("1,2,0,4")).unwrap_err(),
            "rect w/h must be positive: 1,2,0,4"
        );
    }

    #[test]
    fn sample_to_json_returns_point_colors_and_rect_average() {
        let mut image = RgbaImage::new(4, 4);
        image.put_pixel(0, 0, Rgba([255, 0, 0, 255]));
        image.put_pixel(1, 0, Rgba([0, 255, 0, 255]));
        let json = sample_to_json(
            &image,
            &[Point { x: 0, y: 0 }, Point { x: 1, y: 0 }],
            Some([0, 0, 2, 1]),
        )
        .unwrap();
        assert_eq!(
            json,
            "{\"imageWidth\":4,\"imageHeight\":4,\"points\":[{\"x\":0,\"y\":0,\"rgbaHex\":\"#FF0000FF\"},\
             {\"x\":1,\"y\":0,\"rgbaHex\":\"#00FF00FF\"}],\"rectAverage\":{\"x\":0,\"y\":0,\"w\":2,\"h\":1,\
             \"rgbHex\":\"#7F7F00\"}}"
        );
    }

    #[test]
    fn sample_to_json_rejects_out_of_bounds_point_and_rect() {
        let image = RgbaImage::new(4, 4);
        assert_eq!(
            sample_to_json(&image, &[Point { x: 4, y: 0 }], None).unwrap_err(),
            "point (4,0) is outside the image 4x4"
        );
        assert_eq!(
            sample_to_json(&image, &[], Some([10, 10, 2, 2])).unwrap_err(),
            "rect is outside the image 4x4"
        );
    }

    #[test]
    fn java_split_semantics() {
        assert_eq!(java_split("", ','), vec![""]);
        assert_eq!(java_split(",,", ','), Vec::<&str>::new());
        assert_eq!(java_split("a,,b,,", ','), vec!["a", "", "b"]);
    }
}
