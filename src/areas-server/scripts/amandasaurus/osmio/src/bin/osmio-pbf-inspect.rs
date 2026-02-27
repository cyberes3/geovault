// Count objects by type using arcpbf with a plain BufReader (no progress bar).
// Use to verify whether the reader sees ways in a PBF.
use osmio::arcpbf::PBFReader;
use osmio::obj_types::ArcOSMObj;
use osmio::OSMReader;
use std::env;
use std::fs::File;
use std::io::BufReader;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let filename = env::args()
        .nth(1)
        .expect("usage: osmio-pbf-inspect <file.pbf>");

    let file = File::open(&filename)?;
    let reader = BufReader::new(file);
    let mut pbf = PBFReader::new(reader);

    let mut nodes = 0u64;
    let mut ways = 0u64;
    let mut relations = 0u64;

    for obj in pbf.objects() {
        match obj {
            ArcOSMObj::Node(_) => nodes += 1,
            ArcOSMObj::Way(_) => ways += 1,
            ArcOSMObj::Relation(_) => relations += 1,
        }
    }

    println!("{}: nodes={} ways={} relations={}", filename, nodes, ways, relations);
    Ok(())
}
