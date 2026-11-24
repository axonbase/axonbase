-- Motoristas por perto: tabela, índice geoespacial e dados de exemplo (40 motoristas em volta da Praça da Sé).
-- Use um banco só para este exemplo (por exemplo app/corrida) e rode com "Run all" no Workspace.
-- Pode ser repetido: CREATE regrava o mesmo registro.
--
-- pos é um ponto GeoJSON: {type: 'Point', coordinates: [longitude, latitude]} (nessa ordem).
DEFINE TABLE motorista SCHEMALESS;
DEFINE INDEX motorista_geo ON TABLE motorista COLUMNS pos GEO;

CREATE motorista:1 CONTENT {nome: 'Ana', placa: 'MOT001', status: 'livre', pos: {type: 'Point', coordinates: [-46.637755, -23.556555]}};
CREATE motorista:2 CONTENT {nome: 'Bruno', placa: 'MOT002', status: 'livre', pos: {type: 'Point', coordinates: [-46.637763, -23.55007]}};
CREATE motorista:3 CONTENT {nome: 'Carla', placa: 'MOT003', status: 'livre', pos: {type: 'Point', coordinates: [-46.642004, -23.547276]}};
CREATE motorista:4 CONTENT {nome: 'Diego', placa: 'MOT004', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.647269, -23.546828]}};
CREATE motorista:5 CONTENT {nome: 'Elisa', placa: 'MOT005', status: 'livre', pos: {type: 'Point', coordinates: [-46.630871, -23.550578]}};
CREATE motorista:6 CONTENT {nome: 'Fabio', placa: 'MOT006', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.635902, -23.549285]}};
CREATE motorista:7 CONTENT {nome: 'Gabi', placa: 'MOT007', status: 'livre', pos: {type: 'Point', coordinates: [-46.643072, -23.536705]}};
CREATE motorista:8 CONTENT {nome: 'Hugo', placa: 'MOT008', status: 'livre', pos: {type: 'Point', coordinates: [-46.635422, -23.561532]}};
CREATE motorista:9 CONTENT {nome: 'Iara', placa: 'MOT009', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.6366, -23.549967]}};
CREATE motorista:10 CONTENT {nome: 'João', placa: 'MOT010', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.628637, -23.543659]}};
CREATE motorista:11 CONTENT {nome: 'Karen', placa: 'MOT011', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.62672, -23.540052]}};
CREATE motorista:12 CONTENT {nome: 'Lucas', placa: 'MOT012', status: 'livre', pos: {type: 'Point', coordinates: [-46.630763, -23.551123]}};
CREATE motorista:13 CONTENT {nome: 'Marta', placa: 'MOT013', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.635722, -23.540052]}};
CREATE motorista:14 CONTENT {nome: 'Nando', placa: 'MOT014', status: 'livre', pos: {type: 'Point', coordinates: [-46.636853, -23.554669]}};
CREATE motorista:15 CONTENT {nome: 'Olga', placa: 'MOT015', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.649588, -23.55983]}};
CREATE motorista:16 CONTENT {nome: 'Paulo', placa: 'MOT016', status: 'livre', pos: {type: 'Point', coordinates: [-46.649053, -23.561782]}};
CREATE motorista:17 CONTENT {nome: 'Quiteria', placa: 'MOT017', status: 'livre', pos: {type: 'Point', coordinates: [-46.622147, -23.570566]}};
CREATE motorista:18 CONTENT {nome: 'Rafa', placa: 'MOT018', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.60486, -23.55141]}};
CREATE motorista:19 CONTENT {nome: 'Sueli', placa: 'MOT019', status: 'livre', pos: {type: 'Point', coordinates: [-46.586571, -23.579394]}};
CREATE motorista:20 CONTENT {nome: 'Tiago', placa: 'MOT020', status: 'livre', pos: {type: 'Point', coordinates: [-46.683656, -23.529948]}};
CREATE motorista:21 CONTENT {nome: 'Ulisses', placa: 'MOT021', status: 'livre', pos: {type: 'Point', coordinates: [-46.660447, -23.551369]}};
CREATE motorista:22 CONTENT {nome: 'Vera', placa: 'MOT022', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.629738, -23.587826]}};
CREATE motorista:23 CONTENT {nome: 'Wagner', placa: 'MOT023', status: 'livre', pos: {type: 'Point', coordinates: [-46.655065, -23.567844]}};
CREATE motorista:24 CONTENT {nome: 'Xuxa', placa: 'MOT024', status: 'livre', pos: {type: 'Point', coordinates: [-46.682954, -23.510931]}};
CREATE motorista:25 CONTENT {nome: 'Yuri', placa: 'MOT025', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.585122, -23.525001]}};
CREATE motorista:26 CONTENT {nome: 'Zeca', placa: 'MOT026', status: 'livre', pos: {type: 'Point', coordinates: [-46.648008, -23.570489]}};
CREATE motorista:27 CONTENT {nome: 'Bete', placa: 'MOT027', status: 'livre', pos: {type: 'Point', coordinates: [-46.627465, -23.611639]}};
CREATE motorista:28 CONTENT {nome: 'Caio', placa: 'MOT028', status: 'livre', pos: {type: 'Point', coordinates: [-46.662896, -23.560375]}};
CREATE motorista:29 CONTENT {nome: 'Dora', placa: 'MOT029', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.6198, -23.516498]}};
CREATE motorista:30 CONTENT {nome: 'Edu', placa: 'MOT030', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.590286, -23.501806]}};
CREATE motorista:31 CONTENT {nome: 'Flavia', placa: 'MOT031', status: 'livre', pos: {type: 'Point', coordinates: [-46.657709, -23.541002]}};
CREATE motorista:32 CONTENT {nome: 'Gil', placa: 'MOT032', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.626205, -23.612385]}};
CREATE motorista:33 CONTENT {nome: 'Helena', placa: 'MOT033', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.632104, -23.613336]}};
CREATE motorista:34 CONTENT {nome: 'Ivo', placa: 'MOT034', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.684628, -23.563992]}};
CREATE motorista:35 CONTENT {nome: 'Julia', placa: 'MOT035', status: 'livre', pos: {type: 'Point', coordinates: [-46.653695, -23.564588]}};
CREATE motorista:36 CONTENT {nome: 'Kleber', placa: 'MOT036', status: 'livre', pos: {type: 'Point', coordinates: [-46.604554, -23.525927]}};
CREATE motorista:37 CONTENT {nome: 'Lia', placa: 'MOT037', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.655632, -23.561385]}};
CREATE motorista:38 CONTENT {nome: 'Marcos', placa: 'MOT038', status: 'livre', pos: {type: 'Point', coordinates: [-46.655957, -23.493436]}};
CREATE motorista:39 CONTENT {nome: 'Nina', placa: 'MOT039', status: 'ocupado', pos: {type: 'Point', coordinates: [-46.616695, -23.525167]}};
CREATE motorista:40 CONTENT {nome: 'Otavio', placa: 'MOT040', status: 'livre', pos: {type: 'Point', coordinates: [-46.634082, -23.509435]}};
