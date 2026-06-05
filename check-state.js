const { ethers } = require('ethers');
const provider = new ethers.JsonRpcProvider('https://eth-sepolia.g.alchemy.com/v2/48rdcPmOO2K1i8uWVwVss');
const ABI = [
  "function walletLimit() view returns (uint256)",
  "function totalAllocated() view returns (uint256)",
  "function owner() view returns (address)"
];
const contract = new ethers.Contract('0x5aef3f64351a4c5903ad9d1e4a2a8f89760e28f1', ABI, provider);
async function main() {
  const [limit, allocated, owner] = await Promise.all([
    contract.walletLimit(), contract.totalAllocated(), contract.owner()
  ]);
  const D = 10n**18n;
  console.log(`walletLimit:    ${limit/D} KRW`);
  console.log(`totalAllocated: ${allocated/D} KRW`);
  console.log(`owner:          ${owner}`);
  console.log(`가용 한도:      ${(limit - allocated)/D} KRW`);
}
main().catch(console.error);
